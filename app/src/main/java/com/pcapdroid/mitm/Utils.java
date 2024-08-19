/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2023 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import java.util.List;

public class Utils {
    public static void showHintDialog(Context ctx, int id) {
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.hint);
        builder.setMessage(ctx.getString(id));
        builder.setCancelable(true);
        builder.setNeutralButton(android.R.string.ok,
                (dialog, id1) -> dialog.cancel());

        AlertDialog alert = builder.create();
        alert.show();

        TextView message = (TextView)alert.findViewById(android.R.id.message);
        if(message != null) {
            message.setMovementMethod(LinkMovementMethod.getInstance());
            message.setText(id);
        }
    }

    public static boolean hasPersistableReadPermission(Context ctx, Uri uri) {
        List<UriPermission> persistableUris = ctx.getContentResolver().getPersistedUriPermissions();

        for(UriPermission perm: persistableUris) {
            if(perm.getUri().equals(uri) && perm.isReadPermission())
                return true;
        }

        return false;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    public static String uriToFilePath(Context ctx, Uri uri) {
        // https://gist.github.com/r0b0t3d/492f375ec6267a033c23b4ab8ab11e6a
        if (DocumentsContract.isDocumentUri(ctx, uri) && isExternalStorageDocument(uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            final String[] split = docId.split(":");
            final String type = split[0];

            if ("primary".equalsIgnoreCase(type))
                return Environment.getExternalStorageDirectory() + "/" + split[1];
        } else if(isDownloadsDocument(uri)) {
            return downloadsUriToPath(ctx, uri);
        } else if("content".equalsIgnoreCase(uri.getScheme()))
            return mediastoreUriToPath(ctx, uri);
        else if ("file".equalsIgnoreCase(uri.getScheme()))
            return uri.getPath();
        return null;
    }

    private static String downloadsUriToPath(Context ctx, Uri uri) {
        final String id = DocumentsContract.getDocumentId(uri);
        if(id == null)
            return null;

        // Starting with Android O, this "id" is not necessarily a long (row number),
        // but might also be a "raw:/some/file/path" URL
        if (id.startsWith("raw:/")) {
            return Uri.parse(id).getPath();
        } else {
            long id_long;
            try {
                id_long = Long.parseLong(id);
            } catch (NumberFormatException ignored) {
                return null;
            }

            String[] contentUriPrefixesToTry = new String[]{
                    "content://downloads/public_downloads",
                    "content://downloads/my_downloads"
            };
            for (String contentUriPrefix : contentUriPrefixesToTry) {
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse(contentUriPrefix), id_long);
                String path = mediastoreUriToPath(ctx, contentUri);
                if(path != null)
                    return path;
            }
        }

        return null;
    }

    private static String mediastoreUriToPath(Context ctx, Uri uri) {
        String[] proj = { MediaStore.Files.FileColumns.DATA };
        try(Cursor cursor = ctx.getContentResolver().query(uri, proj, null, null, null)) {
            if (cursor != null) {
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                if (cursor.moveToFirst())
                    return cursor.getString(column_index);
            }
        } catch (Exception ignored) {}

        return null;
    }
}
