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
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.List;

public class AddonsAdapter extends ArrayAdapter<Addon> {
    public interface AddonListener {
        void onAddonToggled(Addon addon, boolean enabled);
        void onAddonSettingsClicked(Addon addon);
    }

    private final LayoutInflater mLayoutInflater;
    private WeakReference<AddonListener> mListener;

    public AddonsAdapter(Context context) {
        super(context, R.layout.addon_item);

        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if(convertView == null) {
            convertView = mLayoutInflater.inflate(R.layout.addon_item, parent, false);

            convertView.findViewById(R.id.toggle_btn)
                    .setOnClickListener((v) -> {
                        AddonListener listener = mListener.get();
                        if(listener != null)
                            listener.onAddonToggled(getItem(position), ((Switch)v).isChecked());
                    });

            convertView.findViewById(R.id.settings)
                    .setOnClickListener((v) -> {
                        AddonListener listener = mListener.get();
                        if(listener != null)
                            listener.onAddonSettingsClicked(getItem(position));
                    });
        }

        TextView fname = convertView.findViewById(R.id.fname);
        TextView info = convertView.findViewById(R.id.info);
        Switch toggle = convertView.findViewById(R.id.toggle_btn);
        ImageButton settings = convertView.findViewById(R.id.settings);

        Addon addon = getItem(position);
        fname.setText(addon.fname);
        info.setText(addon.description);
        toggle.setChecked(addon.enabled);
        settings.setVisibility((addon.type == Addon.AddonType.UserAddon) ?
                View.GONE : View.VISIBLE);

        return convertView;
    }

    public void setListener(AddonListener listener) {
        mListener = new WeakReference<>(listener);
    }

    public void reload(List<Addon> addons) {
        clear();
        addAll(addons);
    }
}
