/*
 * Copyright (C) 2026 The AshFall Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ashfall.parts;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;

import org.ashfall.parts.ipc.CalClient;
import org.ashfall.parts.ipc.CalData;
import org.ashfall.parts.ipc.RawSample;
import org.ashfall.parts.ipc.RawStream;
import org.ashfall.parts.ui.ControllerTesterView;

import java.io.IOException;

/** Full-screen controller tester.  B/Back exits, Select toggles the raw view. */
public class TesterActivity extends PartsActivity {

    private ControllerTesterView mView;
    private TextView mHint;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private RawStream mRawStream;

    @Override
    protected int getPageTitle() {
        return R.string.tester_title;
    }

    @Override
    protected int getContentLayout() {
        return R.layout.activity_tester;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mView = findViewById(R.id.tester_view);
        mHint = findViewById(R.id.tester_hint);
        mView.setFocusable(true);
        mView.requestFocus();

        new Thread(() -> {
            CalClient client = null;
            CalData data = null;
            try {
                client = CalClient.open();
                data = client.getCal();
            } catch (Exception ignored) {
                // The tester works without a daemon; only the raw view needs it.
            } finally {
                CalClient.closeQuietly(client);
            }
            final CalData calibration = data;
            mHandler.post(() -> mView.setCalibration(calibration));
        }, "ashfall-tester-cal").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startRaw();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopRaw();
    }

    private void startRaw() {
        stopRaw();
        try {
            mRawStream = RawStream.open(new RawStream.Listener() {
                @Override
                public void onSample(RawSample sample) {
                    mHandler.post(() -> mView.setRaw(sample));
                }

                @Override
                public void onClosed(IOException error) {
                    mHandler.post(() -> {
                        mView.setRawAvailable(false);
                        if (mHint != null) {
                            mHint.setText(R.string.tester_no_raw);
                        }
                    });
                }
            });
        } catch (IOException e) {
            mView.setRawAvailable(false);
            if (mHint != null) {
                mHint.setText(R.string.tester_no_raw);
            }
        }
    }

    private void stopRaw() {
        if (mRawStream != null) {
            mRawStream.close();
            mRawStream = null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                finish();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mView.toggleRaw();
                if (mHint != null) {
                    mHint.setText(mView.isShowingRaw() ? R.string.tester_hint
                            : R.string.tester_hint_no_raw);
                }
            }
            return true;
        }
        if (mView != null && mView.handleKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mView != null && mView.handleMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
}
