package com.pulse.player;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only provider granting the system installer access to one verified APK. */
public final class UpdateApkProvider extends ContentProvider {
    public static final String AUTHORITY = "com.pulse.player.updatefiles";
    public static final Uri APK_URI = Uri.parse("content://" + AUTHORITY + "/pulseplayer-update.apk");

    static File updateFile(android.content.Context ctx) {
        return new File(new File(ctx.getCacheDir(), "verified_updates"), "pulseplayer-update.apk");
    }

    private void validate(Uri uri) throws FileNotFoundException {
        if (!APK_URI.equals(uri)) throw new FileNotFoundException("Not an update package");
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return APK_URI.equals(uri) ?
        "application/vnd.android.package-archive" : null; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        validate(uri);
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        File file = updateFile(getContext());
        if (!file.isFile()) throw new FileNotFoundException("Update is unavailable");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!APK_URI.equals(uri)) return null;
        String[] columns = projection == null ?
            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i=0; i<columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]="PulsePlayer-update.apk";
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i]=updateFile(getContext()).length();
        }
        cursor.addRow(row);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
}
