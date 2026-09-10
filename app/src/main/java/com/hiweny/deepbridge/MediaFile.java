package com.hiweny.deepbridge;

import android.util.Base64;

/** 准备传给 DeepSeek 网页的一个附件（已解密、已做尺寸治理）。 */
public class MediaFile {
    public final String name;
    public final String mime;
    public final byte[] data;

    public MediaFile(String name, String mime, byte[] data) {
        this.name = name;
        this.mime = mime;
        this.data = data;
    }

    public String base64() {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public int size() { return data == null ? 0 : data.length; }
}
