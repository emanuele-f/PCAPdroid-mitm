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
import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

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
}
