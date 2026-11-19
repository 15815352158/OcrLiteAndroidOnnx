package com.benjaminwan.ocr.invoice.utils;

import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Token 密码框选择工具栏回调：屏蔽复制/剪切/分享，防止明文被带出（粘贴不受影响）。
 * 注：必须为顶层具名类，匿名内部类会触发本工程旧版 D8 的 MethodParameters NPE。
 */
public class NoCopyActionModeCallback implements ActionMode.Callback {

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        menu.removeItem(android.R.id.copy);
        menu.removeItem(android.R.id.cut);
        menu.removeItem(android.R.id.shareText);
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        int id = item.getItemId();
        // 拦截复制/剪切/分享操作，粘贴(paste)不受影响
        return id == android.R.id.copy || id == android.R.id.cut
                || id == android.R.id.shareText;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
    }
}
