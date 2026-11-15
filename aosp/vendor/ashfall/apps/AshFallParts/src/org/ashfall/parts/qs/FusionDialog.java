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

package org.ashfall.parts.qs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.ashfall.parts.R;
import org.ashfall.parts.ipc.FusionClient;
import org.ashfall.parts.ui.Ui;

import java.util.Collections;
import java.util.List;

/**
 * The Fusion Quick Settings dialog: the main switch, the connected pads with
 * their layout picker, Rescan and a link to the Controller page.
 *
 * <p>The pad rows come from {@code FUSION STATUS} (docs/phase6.md §9.8).  The
 * layout options expand in place: a second dialog window would be drawn behind
 * the Quick Settings window and could not be reached (the Lighting dialog keeps
 * its picker inline for the same reason).
 */
public class FusionDialog extends Dialog {

    public interface Listener {
        /** A setting changed; the tile refreshes its label and state. */
        void onChanged();

        /** "More settings": open the Controller page. */
        void onMoreSettings();
    }

    private static final long TICK_MS = 2000;

    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            refreshAsync();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    private Switch mSwitch;
    private TextView mState;
    private LinearLayout mPads;
    private boolean mUpdating;
    private volatile boolean mFetching;
    private String mSignature = "";
    private String mExpandedKey;

    public FusionDialog(@NonNull Context context, Listener listener) {
        super(context, R.style.Theme_AshFall_QsDialog);
        mListener = listener;
        setCanceledOnTouchOutside(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.fusion_qs_dialog);

        mSwitch = findViewById(R.id.fusion_qs_switch);
        mState = findViewById(R.id.fusion_qs_state);
        mPads = findViewById(R.id.fusion_qs_pads);

        mSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mUpdating) {
                return;
            }
            FusionClient.setOn(checked);
            updateState(null);
            apply(checked ? "FUSION ON" : "FUSION OFF", 0);
        });
        findViewById(R.id.fusion_qs_rescan).setOnClickListener(
                view -> apply("FUSION RESCAN", R.string.fusion_rescan_done));
        findViewById(R.id.fusion_qs_more).setOnClickListener(view -> {
            if (mListener != null) {
                mListener.onMoreSettings();
            }
            dismiss();
        });
        findViewById(R.id.fusion_qs_done).setOnClickListener(view -> dismiss());

        updateState(FusionClient.cache());
        rebuild(FusionClient.cache());
        fitWindow();
        mSwitch.requestFocus();
    }

    /**
     * Sizes the dialog after the runtime rows exist (and again whenever one
     * expands).  {@code Ui.fitDialog()} is not enough here: it measures the
     * root at the 85 % cap, so once the expanded pad list reaches that cap the
     * measured height equals the target and the {@code ScrollView} keeps
     * {@code wrap_content}, which pushes the footer out of the window.  The
     * natural height is therefore measured without the cap first, and the
     * scroll area is the fixed remainder.
     */
    private void fitWindow() {
        final Window window = getWindow();
        if (window == null) {
            return;
        }
        final View root = findViewById(R.id.fusion_qs_root);
        final View scroll = findViewById(R.id.fusion_qs_scroll);
        if (root == null || scroll == null) {
            return;
        }
        final Context context = getContext();
        final int width = Ui.dp(context, 440);
        final int screen = root.getResources().getDisplayMetrics().heightPixels;
        final int maxHeight = Math.round(screen * 0.85f);
        final int minHeight = Ui.dp(context, 280);

        final ViewGroup.LayoutParams params = scroll.getLayoutParams();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(params);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int natural = root.getMeasuredHeight();
        final int target = Math.min(Math.max(natural, minHeight), maxHeight);
        if (natural > maxHeight) {
            final int chrome = Math.max(0, natural - scroll.getMeasuredHeight());
            params.height = Math.max(Ui.dp(context, 120), target - chrome);
            scroll.setLayoutParams(params);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.AT_MOST));
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.setLayout(width, target);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mHandler.removeCallbacks(mTick);
        mHandler.post(mTick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacks(mTick);
    }

    private void refreshAsync() {
        if (mFetching) {
            return;
        }
        mFetching = true;
        new Thread(() -> {
            final FusionClient.Status status = FusionClient.query();
            mHandler.post(() -> {
                mFetching = false;
                if (!isShowing()) {
                    return;
                }
                updateState(status);
                rebuild(status);
            });
        }, "ashfall-fusion-dialog").start();
    }

    private void updateState(FusionClient.Status status) {
        if (mSwitch == null) {
            return;
        }
        mUpdating = true;
        try {
            mSwitch.setChecked(FusionClient.isOn());
        } finally {
            mUpdating = false;
        }
        mState.setText(FusionClient.stateText(getContext(), status));
    }

    /** Rebuilds the pad rows, but only when the daemon's list changed. */
    private void rebuild(FusionClient.Status status) {
        if (mPads == null) {
            return;
        }
        final boolean available = status != null && status.available;
        final List<FusionClient.Pad> pads = available
                ? status.pads : Collections.emptyList();
        final StringBuilder signature = new StringBuilder(available ? "ok" : "down");
        for (FusionClient.Pad pad : pads) {
            signature.append('|').append(pad.key).append(':').append(pad.layout);
        }
        if (signature.toString().equals(mSignature)) {
            return;
        }
        mSignature = signature.toString();

        mPads.removeAllViews();
        if (pads.isEmpty()) {
            final TextView note = new TextView(getContext());
            note.setText(available
                    ? R.string.fusion_pads_none : R.string.fusion_state_unavailable);
            note.setTextAppearance(R.style.AshFallDialogSecondary);
            mPads.addView(note);
            return;
        }
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        for (FusionClient.Pad pad : pads) {
            mPads.addView(buildPadRow(inflater, pad));
        }
        fitWindow();
    }

    private View buildPadRow(LayoutInflater inflater, FusionClient.Pad pad) {
        final View row = inflater.inflate(R.layout.fusion_qs_pad, mPads, false);
        final TextView title = row.findViewById(R.id.fusion_qs_pad_title);
        final TextView summary = row.findViewById(R.id.fusion_qs_pad_summary);
        final RadioGroup group = row.findViewById(R.id.fusion_qs_pad_layouts);
        final TextView hint = row.findViewById(R.id.fusion_qs_pad_hint);

        title.setText(pad.name);
        summary.setText(FusionClient.modeName(getContext(), pad.layout));

        for (int i = 0; i < FusionClient.MODES.length; i++) {
            final RadioButton option = new RadioButton(getContext());
            option.setId(View.generateViewId());
            option.setTag(FusionClient.MODES[i]);
            option.setText(FusionClient.MODE_LABELS[i]);
            option.setChecked(FusionClient.MODES[i].equals(pad.layout));
            option.setLayoutParams(new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(getContext(), 40)));
            group.addView(option);
        }
        group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
            if (mUpdating) {
                return;
            }
            final View checked = radioGroup.findViewById(checkedId);
            if (checked == null || !(checked.getTag() instanceof String)) {
                return;
            }
            final String mode = (String) checked.getTag();
            hint.setVisibility(FusionClient.MODE_CUSTOM.equals(mode)
                    ? View.VISIBLE : View.GONE);
            summary.setText(FusionClient.MODE_LABELS[FusionClient.modeIndex(mode)]);
            FusionClient.setLayoutFor(pad.key, mode);
            apply("FUSION LAYOUT " + pad.key + " " + mode, R.string.fusion_layout_saved);
        });

        final boolean expanded = pad.key.equals(mExpandedKey);
        group.setVisibility(expanded ? View.VISIBLE : View.GONE);
        hint.setVisibility(expanded && FusionClient.MODE_CUSTOM.equals(pad.layout)
                ? View.VISIBLE : View.GONE);
        row.findViewById(R.id.fusion_qs_pad_header).setOnClickListener(view -> {
            final boolean show = group.getVisibility() != View.VISIBLE;
            group.setVisibility(show ? View.VISIBLE : View.GONE);
            hint.setVisibility(show && FusionClient.MODE_CUSTOM.equals(pad.layout)
                    ? View.VISIBLE : View.GONE);
            mExpandedKey = show ? pad.key : null;
            if (show) {
                focusChecked(group);
            }
            fitWindow();
        });
        return row;
    }

    private void focusChecked(RadioGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            if (child instanceof RadioButton && ((RadioButton) child).isChecked()) {
                child.requestFocus();
                return;
            }
        }
        if (group.getChildCount() > 0) {
            group.getChildAt(0).requestFocus();
        }
    }

    /** Sends one write command and refreshes what it changed. */
    private void apply(String command, int doneMessage) {
        new Thread(() -> {
            String error = null;
            FusionClient client = null;
            try {
                client = FusionClient.open();
                client.command(command);
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                FusionClient.closeQuietly(client);
            }
            final boolean failed = error != null;
            mHandler.post(() -> {
                if (failed) {
                    Toast.makeText(getContext(), R.string.not_connected,
                            Toast.LENGTH_SHORT).show();
                } else if (doneMessage != 0) {
                    Toast.makeText(getContext(), doneMessage, Toast.LENGTH_SHORT).show();
                }
                if (mListener != null) {
                    mListener.onChanged();
                }
                refreshAsync();
            });
        }, "ashfall-fusion-command").start();
    }
}
