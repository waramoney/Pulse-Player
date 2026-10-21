package com.pulse.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Offline, user-approved APK updates. The user selects a folder once via Android's
 * Storage Access Framework (SAF). At most once per day the app checks for a newer
 * PulsePlayer APK placed there. No network, GitHub, Firebase, account or polling.
 * Every candidate is checked against the installed package ID, version and signer.
 */
final class AppUpdateManager {
    static final int REQUEST_UPDATE_FOLDER = 1008;
    static final int REQUEST_UPDATE_APK = 1009;
    private static final String KEY_FOLDER = "offline_update_folder";
    private static final String KEY_LAST_CHECK = "offline_update_check_at";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long MAX_APK_SIZE = 100L * 1024L * 1024L;
    private static final int MAX_FILES_TO_CHECK = 6;
    private final Activity activity;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    AppUpdateManager(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("pulse", 0);
    }

    void selectUpdateFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_UPDATE_FOLDER);
        } catch (Exception e) {
            message("폴더 선택기를 열 수 없습니다.");
        }
    }

    void selectUpdateApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_UPDATE_APK);
        } catch (Exception e) {
            message("업데이트 APK 선택기를 열 수 없습니다.");
        }
    }

    void handleSelection(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_UPDATE_FOLDER) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            try {
                activity.getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                prefs.edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_LAST_CHECK).apply();
                message("업데이트 폴더를 저장했습니다. 이후 앱 실행 시 새 APK를 확인합니다.");
                checkForUpdates(true);
            } catch (SecurityException e) {
                message("선택한 폴더의 읽기 권한을 허용할 수 없습니다.");
            }
        } else if (requestCode == REQUEST_UPDATE_APK) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
            checkApk(data.getData());
        }
    }

    void checkForUpdates(boolean manual) {
        final String saved = prefs.getString(KEY_FOLDER, "");
        if (saved == null || saved.isEmpty()) {
            if (manual) activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                    .setTitle("업데이트 폴더 선택")
                    .setMessage("GitHub나 서버 없이 업데이트합니다. 새 APK를 넣어둘 폴더를 한 번 지정하면 앱 실행 시 새 버전을 확인합니다.")
                    .setNegativeButton("취소", (d,w) -> {})
                    .setPositiveButton("폴더 선택", (d,w) -> selectUpdateFolder()).show());
            return;
        }
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return;
        if (!busy.compareAndSet(false, true)) return;
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        worker.execute(() -> {
            try {
                List<UpdateDocument> docs = findUpdateFiles(Uri.parse(saved));
                Collections.sort(docs, (a,b) -> Long.compare(b.modified, a.modified));
                boolean found = false;
                for (int i = 0; i < Math.min(docs.size(), MAX_FILES_TO_CHECK); i++) {
                    try {
                        if (prepareCandidate(docs.get(i).uri)) { found = true; break; }
                    } catch (SignatureMismatchException incompatible) {
                        message("기존 앱과 APK 서명이 달라 직접 업그레이드할 수 없습니다. 데이터를 백업한 뒤 서명을 확인해 주세요.");
                        found = true;
                        break;
                    } catch (Exception ignored) {
                        // One corrupt or inaccessible APK must not block other candidates.
                    }
                }
                if (!found && manual) message("선택한 폴더에 설치 가능한 더 높은 버전의 APK가 없습니다.");
            } catch (Exception e) {
                if (manual) message("폴더 접근에 실패했습니다. 업데이트 폴더를 다시 지정해 주세요.");
            } finally {
                busy.set(false);
            }
        });
    }

    private static final class UpdateDocument {
        final Uri uri;
        final long modified;
        UpdateDocument(Uri uri,long modified) {this.uri=uri;this.modified=modified;}
    }

    private List<UpdateDocument> findUpdateFiles(Uri tree) throws Exception {
        List<UpdateDocument> result = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = activity.getContentResolver().query(children, columns,
                null, null, null)) {
            if (cursor == null) throw new IOException("Folder is not readable");
            int count = 0;
            while (cursor.moveToNext() && count++ < 500) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                long size = cursor.isNull(2) ? -1 : cursor.getLong(2);
                long modified = cursor.isNull(3) ? 0 : cursor.getLong(3);
                if (name == null || documentId == null) continue;
                String normalized = name.toLowerCase(Locale.ROOT);
                if (!(normalized.startsWith("pulseplayer") || normalized.startsWith("pulse-player")) ||
                        !normalized.endsWith(".apk") || size > MAX_APK_SIZE || size == 0) continue;
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, documentId);
                result.add(new UpdateDocument(doc, modified));
            }
        }
        return result;
    }

    void checkApk(Uri documentUri) {
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                if (!prepareCandidate(documentUri)) message("이미 최신 버전이거나 유효하지 않은 APK입니다.");
            } catch (SignatureMismatchException e) {
                message("APK 서명이 기존 앱과 다릅니다. 데이터 백업 후 같은 서명으로 빌드한 APK를 사용해 주세요.");
            } catch (Exception e) {
                message("APK 파일을 읽거나 검증할 수 없습니다.");
            } finally { busy.set(false); }
        });
    }

    /** Copy the SAF URI to private cache and verify package, version and signer before approval. */
    private boolean prepareCandidate(Uri documentUri) throws Exception {
        File target = UpdateApkProvider.updateFile(activity);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cache unavailable");
        File temp = new File(parent, "pending-update.apk");
        try {
            long count = 0L;
            try (InputStream input = activity.getContentResolver().openInputStream(documentUri);
                 FileOutputStream output = new FileOutputStream(temp)) {
                if (input == null) throw new IOException("Cannot open APK");
                byte[] bytes = new byte[8192];
                for (int n; (n = input.read(bytes)) != -1;) {
                    count += n;
                    if (count > MAX_APK_SIZE) throw new IOException("APK too large");
                    output.write(bytes,0,n);
                }
                output.flush();
                output.getFD().sync();
            }
            if (count < 10240L) throw new IOException("APK too small");
            // Verify the temporary file *before* replacing the installer's read-only file.
            PackageInfo candidate = archiveInfo(temp);
            if (candidate == null || !activity.getPackageName().equals(candidate.packageName))
                throw new IOException("Not a Pulse Player APK");
            PackageInfo installed = installedInfo();
            if (versionCode(candidate) <= versionCode(installed)) return false;
            if (!java.util.Arrays.equals(signers(candidate),signers(installed)) ||
                signers(candidate).length == 0) throw new SignatureMismatchException();
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace old package");
            if (!temp.renameTo(target)) throw new IOException("Cannot finish verified APK");
            showInstall(versionLabel(candidate));
            return true;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private PackageInfo archiveInfo(File file) {
        return activity.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }

    private PackageInfo installedInfo() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }

    private static long versionCode(PackageInfo packageInfo) {
        return Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null)
            return info.signingInfo.getApkContentsSigners();
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static String versionLabel(PackageInfo info) {
        return info.versionName == null ? String.valueOf(versionCode(info)) : info.versionName;
    }

    private static final class SignatureMismatchException extends Exception { }

    private void showInstall(String versionName) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            new AlertDialog.Builder(activity)
                .setTitle("Pulse Player " + versionName + " 업데이트")
                .setMessage("기기에 있는 APK의 패키지·버전·서명을 확인했습니다. 설치를 승인하면 기존 앱과 플레이리스트·가사가 유지됩니다.")
                .setNegativeButton("나중에", (dialog,which) -> {})
                .setPositiveButton("업데이트 설치", (dialog,which) -> install())
                .show();
        });
    }

    private void install() {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("앱 업데이트 설치 권한")
                .setMessage("한 번만 '이 출처 허용'을 켜고 돌아와 새 버전 확인을 누르면 됩니다.")
                .setNegativeButton("취소", (dialog,which) -> {})
                .setPositiveButton("설정 열기", (dialog,which) -> activity.startActivity(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:"+activity.getPackageName())))).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(UpdateApkProvider.APK_URI,"application/vnd.android.package-archive");
            intent.setClipData(ClipData.newRawUri("PulsePlayer update",UpdateApkProvider.APK_URI));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) { message("설치 앱을 열지 못했습니다."); }
    }

    void shutdown() { worker.shutdownNow(); }

    private void message(String text) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed())
                Toast.makeText(activity,text,Toast.LENGTH_LONG).show();
        });
    }
}
