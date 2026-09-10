package com.hiweny.deepbridge;

import android.content.Context;

/** 极简配色：深/浅两套，全部用整型 ARGB。 */
final class Theme {
    final boolean dark;

    private Theme(boolean dark) { this.dark = dark; }

    static Theme of(Context ctx) {
        return new Theme((ctx.getResources().getConfiguration().uiMode & 48) == 32);
    }

    int bg() { return dark ? -15723492 : -854792; }
    int headerBg() { return dark ? -15262166 : -1; }
    int cardBg() { return dark ? -14998735 : -1; }
    int text() { return dark ? -1512206 : -15065040; }
    int textSub() { return dark ? -6641992 : -10853262; }
    int accent() { return dark ? -10772993 : -13800225; }
    int divider() { return dark ? -14274495 : -1841170; }
    int btnBg() { return dark ? -14471873 : -1; }
    int btnStroke() { return dark ? -13418406 : -2564376; }
    int amber() { return -678365; }
    int green() { return -15156132; }
    int red() { return -1750963; }
}
