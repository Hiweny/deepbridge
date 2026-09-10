package com.hiweny.deepbridge;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

/** 附件发给 DeepSeek 前的治理：图片限制最长边与体积，其它文件原样透传。 */
public final class MediaPrepare {
    private static final int MAX_EDGE = 2048;
    private static final int IMAGE_SOFT_BYTES = 4 * 1024 * 1024;

    private MediaPrepare() {}

    public static MediaFile prepare(MediaFile in) {
        if (in == null || in.data == null) return in;
        if (!in.mime.startsWith("image/")) return in; // 文档/其它文件原样
        try {
            boolean needScale = false;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(in.data, 0, in.data.length, bounds);
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest > MAX_EDGE || in.data.length > IMAGE_SOFT_BYTES) needScale = true;
            if (!needScale) return in;

            int sample = 1;
            int target = longest;
            while (target / 2 >= MAX_EDGE) { target /= 2; sample *= 2; }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(in.data, 0, in.data.length, opt);
            if (bmp == null) return in;

            int longEdge = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longEdge > MAX_EDGE) {
                float scale = MAX_EDGE / (float) longEdge;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int quality = 90;
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            while (bos.toByteArray().length > IMAGE_SOFT_BYTES && quality > 50) {
                bos.reset();
                quality -= 10;
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            }
            byte[] out = bos.toByteArray();
            bmp.recycle();
            String base = in.name == null ? "image" : in.name.replaceAll("\\.[^.]+$", "");
            Util.log("图片治理: " + in.data.length + "B -> " + out.length + "B (q" + quality + ")");
            return new MediaFile(base + ".jpg", "image/jpeg", out);
        } catch (Exception e) {
            Util.log("图片治理失败，原样发送: " + e.getMessage());
            return in;
        }
    }
}
