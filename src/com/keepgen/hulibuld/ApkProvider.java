package com.keepgen.hulibuld;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读的 FileProvider。给「应用内下载 APK 后调用安装器」用。
 *
 * 为什么不直接用 file://：targetSdk 24 以上，把 file:// 传给别的应用会抛
 * FileUriExposedException。必须走 content:// 并临时授权。
 *
 * 不引 androidx，所以自己实现一个最小 Provider：
 *   · 只允许读自己 cache 目录下的 apk
 *   · 用路径校验挡住 ../../ 这类穿越
 */
public class ApkProvider extends ContentProvider {

    static final String AUTHORITY = "com.keepgen.hulibuld.apk";
    private static final String[] COLS = {
            OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
    };

    @Override
    public boolean onCreate() { return true; }

    /** 把缓存里的 apk 文件包装成 content:// 给安装器 */
    static Uri uriFor(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + fileName);
    }

    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) return null;
        File dir = new File(getContext().getCacheDir(), "apk");
        return new File(dir, name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.exists()) return null;
        String[] cols = projection != null ? projection : COLS;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
