package com.wlaz;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 安装进度弹窗 —— 直接监听原版腕连的本地广播：
 *   ACTION_INSTALL_PROGRESS  : progress(int) + indeterminate(boolean)
 *   ACTION_INSTALL_MESSAGE   : message(String)
 *   ACTION_INSTALL_FINISHED  : 完成
 *   ACTION_INSTALL_FAILED    : 失败（message）
 * 因为这些是 LocalBroadcastManager 发的，普通 registerReceiver 收不到，
 * 所以用反射注册到 LocalBroadcastManager。
 */
public class InstallProgress {

    public static final String ACTION_PROGRESS = "com.watch.assistant.bridge.ACTION_INSTALL_PROGRESS";
    public static final String ACTION_MESSAGE = "com.watch.assistant.bridge.ACTION_INSTALL_MESSAGE";
    public static final String ACTION_FINISHED = "com.watch.assistant.bridge.ACTION_INSTALL_FINISHED";
    public static final String ACTION_FAILED = "com.watch.assistant.bridge.ACTION_INSTALL_FAILED";

    public interface Done {
        void onDone(boolean ok, String message);
    }

    private final Activity act;
    private final Done done;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout box;
    private ProgressBar bar;
    private ProgressBar spinner;
    private TextView title;
    private TextView msg;
    private TextView pct;

    private Object lbm;
    private android.app.Dialog dlg;
    private IntentFilter filter;
    private BroadcastReceiver rx;
    private boolean finished = false;
    private int lastProgress = 0;

    public InstallProgress(Activity a, String fileLabel, Done cb) {
        act = a;
        done = cb;
        build(fileLabel);
        register();
        // 兜底：60 秒还没收到 FINSHED/FAILED 就提示可能仍在传输
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                setMessage("仍在传输/等待手表确认…");
            }
        }, 60000);
    }

    private void build(String label) {
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(22);
        box.setPadding(p, dp(20), p, dp(18));
        box.setBackgroundResource(R.drawable.card16);

        title = new TextView(act);
        title.setText("正在安装");
        title.setTextSize(17);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(act.getResources().getColor(R.color.tx));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = new TextView(act);
        sub.setText(label == null ? "" : label);
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setTextColor(act.getResources().getColor(R.color.tx2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        box.addView(sub, slp);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(18);

        bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(8), 1f);

        pct = new TextView(act);
        pct.setText("0%");
        pct.setTextSize(12);
        pct.setTextColor(act.getResources().getColor(R.color.tx2));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
        plp.leftMargin = dp(10);

        row.addView(bar, blp);
        row.addView(pct, plp);
        box.addView(row, rlp);

        spinner = new ProgressBar(act);
        spinner.setVisibility(View.GONE);

        msg = new TextView(act);
        msg.setText("准备中…");
        msg.setTextSize(12);
        msg.setTextColor(act.getResources().getColor(R.color.tx2));
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = dp(12);
        box.addView(msg, mlp);

        android.widget.FrameLayout.LayoutParams wrap = new android.widget.FrameLayout.LayoutParams(
                dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);

        dlg = new android.app.Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(box, wrap);
        dlg.setCancelable(false);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setDimAmount(0.35f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    private void register() {
        try {
            // LocalBroadcastManager 已是本工程源码类，走系统 ClassLoader 即可
            Class<?> c = Class.forName("androidx.localbroadcastmanager.content.LocalBroadcastManager");
            lbm = c.getMethod("getInstance", Context.class).invoke(null, act);
            filter = new IntentFilter();
            filter.addAction(ACTION_PROGRESS);
            filter.addAction(ACTION_MESSAGE);
            filter.addAction(ACTION_FINISHED);
            filter.addAction(ACTION_FAILED);

            rx = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent it) {
                    String a = it.getAction();
                    if (ACTION_PROGRESS.equals(a)) {
                        int v = it.getIntExtra("progress", lastProgress);
                        boolean ind = it.getBooleanExtra("indeterminate", false);
                        updateProgress(v, ind);
                    } else if (ACTION_MESSAGE.equals(a)) {
                        setMessage(it.getStringExtra("message"));
                    } else if (ACTION_FINISHED.equals(a)) {
                        finish(true, "安装完成");
                    } else if (ACTION_FAILED.equals(a)) {
                        finish(false, it.getStringExtra("message"));
                    }
                }
            };
            c.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class)
                    .invoke(lbm, rx, filter);
        } catch (Throwable t) {
            // 拿不到 LocalBroadcastManager 就只显示静态提示
            setMessage("正在传输到手表…");
            Bridge.log("InstallProgress register failed: " + t);
        }
    }

    private void updateProgress(final int v, final boolean indeterminate) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (finished || bar == null) return;
                lastProgress = v;
                bar.setProgress(Math.max(0, Math.min(100, v)));
                pct.setText(Math.max(0, Math.min(100, v)) + "%");
                bar.setVisibility(indeterminate ? View.GONE : View.VISIBLE);
                spinner.setVisibility(indeterminate ? View.VISIBLE : View.GONE);
                if (v >= 100) setMessage("已传输完成，正在手表上安装…");
            }
        });
    }

    private void setMessage(final String s) {
        if (s == null) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (finished || msg == null) return;
                msg.setText(s);
            }
        });
    }

    private void finish(final boolean ok, final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                finished = true;
                dismiss();
                if (done != null) done.onDone(ok, m);
            }
        });
    }

    public void fail(final String m) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                finished = true;
                dismiss();
                if (done != null) done.onDone(false, m);
            }
        });
    }

    private void dismiss() {
        try {
            if (rx != null && lbm != null) {
                Class<?> c = lbm.getClass();
                c.getMethod("unregisterReceiver", BroadcastReceiver.class).invoke(lbm, rx);
            }
        } catch (Throwable ignore) {
        }
        try {
            if (dlg != null) dlg.dismiss();
        } catch (Throwable ignore) {
        }
    }
}