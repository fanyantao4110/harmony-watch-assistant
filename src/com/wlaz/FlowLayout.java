package com.wlaz;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class FlowLayout extends ViewGroup {
    public FlowLayout(Context c) {
        super(c);
    }

    public FlowLayout(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int width = MeasureSpec.getSize(wSpec);
        int padL = getPaddingLeft(), padR = getPaddingRight();
        int padT = getPaddingTop(), padB = getPaddingBottom();
        int maxW = width - padL - padR;
        int lineW = 0, lineH = 0, totalH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            measureChild(v, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), hSpec);
            int cw = v.getMeasuredWidth();
            int ch = v.getMeasuredHeight();
            if (lineW + cw > maxW && lineW > 0) {
                totalH += lineH;
                lineW = 0;
                lineH = 0;
            }
            lineW += cw;
            if (ch > lineH) lineH = ch;
        }
        totalH += lineH;
        setMeasuredDimension(width, totalH + padT + padB);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int padL = getPaddingLeft(), padT = getPaddingTop();
        int maxW = getWidth() - padL - getPaddingRight();
        int x = padL, y = padT, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            int cw = v.getMeasuredWidth();
            int ch = v.getMeasuredHeight();
            if (x - padL + cw > maxW && x > padL) {
                x = padL;
                y += lineH;
                lineH = 0;
            }
            v.layout(x, y, x + cw, y + ch);
            x += cw;
            if (ch > lineH) lineH = ch;
        }
    }
}
