package com.deepseekharness.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/** 编辑态在底栏分发入口接收完整多指流，避免事件被菜单项拆开。 */
public final class EditableBottomNavigationView extends BottomNavigationView {
  private View.OnTouchListener editTouchListener;

  public EditableBottomNavigationView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  void setEditTouchListener(View.OnTouchListener listener) {
    editTouchListener = listener;
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (editTouchListener != null) {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null)
        getParent().requestDisallowInterceptTouchEvent(true);
      return editTouchListener.onTouch(this, event);
    }
    return super.dispatchTouchEvent(event);
  }
}
