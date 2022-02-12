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
 * Copyright 2022 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

public class MitmAddon {
    public static final String PACKAGE_NAME = "com.pcapdroid.mitm";
    public static final String MITM_PERMISSION = "com.pcapdroid.permission.MITM";

    public static final String MITM_SERVICE = PACKAGE_NAME + ".MitmService";
    public static final int MSG_START_MITM = 1;

    public static final String CONTROL_ACTIVITY = PACKAGE_NAME + ".MitmCtrl";
    public static final String ACTION_EXTRA = "action";
    public static final String ACTION_GET_CA_CERTIFICATE = "getCAcert";
    public static final String CERTIFICATE_RESULT = "certificate";
}
