package com.wlaz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class DetailActivity extends Activity {

    private AppItem app;

    @Override
    protected void onCreate(Bundle b) {
        Theme.apply(this);
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        Img.init(getApplicationContext());

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnTheme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                themeDialog();
            }
        });

        int id = getIntent().getIntExtra("id", -1);
        Api.Store s = Api.cached();
        app = s == null ? null : s.byId(id);
        if (app == null) {
            Toast.makeText(this, "数据还没加载好，回主页再进一次", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        bind();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void bind() {
        findViewById(R.id.loading).setVisibility(View.GONE);
        findViewById(R.id.scroll).setVisibility(View.VISIBLE);

        ((TextView) findViewById(R.id.name)).setText(app.name);
        ((TextView) findViewById(R.id.sub)).setText(app.subText());
        Img.load((ImageView) findViewById(R.id.icon), app.icon.length() == 0 ? null : C.SITE + app.icon);

        ((TextView) findViewById(R.id.statSize)).setText(app.size.length() == 0 ? "-" : app.size);
        ((TextView) findViewById(R.id.statDl)).setText(app.dl > 0 ? String.valueOf(app.dl) : "-");
        ((TextView) findViewById(R.id.statRating)).setText(app.rating > 0 ? app.rating + ".0" : "-");
        ((TextView) findViewById(R.id.verNow)).setText(app.ver.length() == 0 ? "-" : "v" + app.ver);
        ((TextView) findViewById(R.id.verUp)).setText(app.up.length() == 0 ? "-" : app.up);
        ((TextView) findViewById(R.id.verDev)).setText(app.devName.length() == 0 ? "-" : app.devName);
        ((TextView) findViewById(R.id.desc)).setText(app.desc == null || app.desc.length() == 0 ? "（暂无介绍）" : app.desc);

        // 获取 / 下载
        TextView get = (TextView) findViewById(R.id.get);
        if (app.downloadable()) {
            get.setText("获取");
            get.setBackgroundResource(R.drawable.get_btn);
            get.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Dl.pick(DetailActivity.this, app);
                }
            });
        } else {
            get.setText("暂无包");
            get.setBackgroundResource(R.drawable.get_btn_off);
            get.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(DetailActivity.this, "这个应用还没有转好的 .app", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 复制链接
        findViewById(R.id.btnCopyLink).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = C.SITE + "app.html?id=" + app.id;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                Toast.makeText(DetailActivity.this, "已复制：" + url, Toast.LENGTH_SHORT).show();
            }
        });

        // 适配设备 chips
        android.view.ViewGroup fg = (android.view.ViewGroup) findViewById(R.id.devFlow);
        fg.removeAllViews();
        List<String> have = app.haveDevs();
        for (String d : app.dev) {
            TextView chip = new TextView(this);
            chip.setText(C.devName(d));
            chip.setTextSize(12);
            chip.setTextColor(getResources().getColor(have.contains(d) ? R.color.blue : R.color.tx2));
            chip.setBackgroundResource(R.drawable.chip_bg);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            lp.bottomMargin = dp(6);
            fg.addView(chip, lp);
        }

        // 截图
        LinearLayout shotsRow = (LinearLayout) findViewById(R.id.shotsRow);
        View shotsBox = findViewById(R.id.shotsBox);
        if (app.shots.isEmpty()) {
            shotsBox.setVisibility(View.GONE);
        } else {
            for (String p : app.shots) {
                ImageView iv = new ImageView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(140), dp(140));
                lp.rightMargin = dp(8);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Img.load(iv, C.SITE + p);
                shotsRow.addView(iv);
            }
        }
    }

    private void themeDialog() {
        final String[] items = new String[]{"跟随系统", "白天", "夜晚"};
        new AlertDialog.Builder(this)
                .setTitle("显示模式")
                .setSingleChoiceItems(items, Theme.mode(this), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Theme.setMode(DetailActivity.this, which);
                        d.dismiss();
                        recreate();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}