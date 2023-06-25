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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class ScriptsAdapter extends ArrayAdapter<IJsUserscript> {
    private final LayoutInflater mLayoutInflater;
    private final String mAuthorLineFmt;

    public ScriptsAdapter(Context context) {
        super(context, R.layout.script_item);

        mAuthorLineFmt = context.getString(R.string.version_and_author);
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if(convertView == null)
            convertView = mLayoutInflater.inflate(R.layout.script_item, parent, false);

        TextView name = convertView.findViewById(R.id.name);
        TextView author = convertView.findViewById(R.id.author);
        TextView descr = convertView.findViewById(R.id.description);

        IJsUserscript script = getItem(position);
        name.setText(script.getName());
        author.setText(script.getVersion().isEmpty() ? script.getAuthor() :
                String.format(mAuthorLineFmt, script.getVersion(), script.getAuthor()));
        descr.setText(script.getDescription());

        return convertView;
    }

    public void reload(List<IJsUserscript> scripts) {
        clear();
        addAll(scripts);
    }
}
