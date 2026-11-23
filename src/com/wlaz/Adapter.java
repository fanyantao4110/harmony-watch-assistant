package com.wlaz;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class Adapter extends BaseAdapter {

    public interface Listener {
        void onOpen(AppItem a);

        void onGet(AppItem a);
    }

    private final Context ctx;
    private final List<AppItem> data = new ArrayList<AppItem>();
    private final Listener listener;

    public Adapter(Context c, Listener l) {
        this.ctx = c;
        this.listener = l;
    }

    public void set(List<AppItem> items) {
        data.clear();
        if (items != null) data.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public AppItem getItem(int p) {
        return data.get(p);
    }

    @Override
    public long getItemId(int p) {
        return data.get(p).id;
    }

    @Override
    public View getView(int pos, View v, ViewGroup parent) {
        if (v == null) v = LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false);
        final AppItem a = data.get(pos);

        ((TextView) v.findViewById(R.id.name)).setText(a.name);
        ((TextView) v.findViewById(R.id.sub)).setText(a.subText());
        ((TextView) v.findViewById(R.id.rating)).setText(a.rating > 0 ? String.valueOf(a.rating) + ".0" : "-");
        ((TextView) v.findViewById(R.id.dl)).setText(a.dl > 0 ? a.dl + " 次下载" : "暂无下载");
        ((TextView) v.findViewById(R.id.devline)).setText(a.adaptLine());

        ImageView icon = (ImageView) v.findViewById(R.id.icon);
        Img.load(icon, a.icon.length() == 0 ? null : C.SITE + a.icon);

        TextView get = (TextView) v.findViewById(R.id.get);
        if (a.downloadable()) {
            get.setText("获取");
            get.setBackgroundResource(R.drawable.get_btn);
            get.setTextColor(0xffffffff);
        } else {
            // 悬赏应用：还没有包，按钮只是标记状态，不引导下载。
            get.setText("求");
            get.setBackgroundResource(R.drawable.get_btn_off);
            get.setTextColor(0xff8e8e93);
        }
        get.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                if (listener != null) listener.onGet(a);
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                if (listener != null) listener.onOpen(a);
            }
        });
        return v;
    }
}