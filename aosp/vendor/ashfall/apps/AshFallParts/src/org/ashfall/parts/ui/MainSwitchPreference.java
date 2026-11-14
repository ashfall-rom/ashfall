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

package org.ashfall.parts.ui;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import org.ashfall.parts.R;

/**
 * The Settings "main switch" row: a large title, an optional live summary and a
 * switch on the right.  The whole row is one focus stop, exactly like the
 * main switch at the top of a Settings page, so the D-pad/A work naturally.
 */
public class MainSwitchPreference extends Preference {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(MainSwitchPreference preference, boolean checked);
    }

    private boolean mChecked;
    private CharSequence mSummaryText;
    private OnCheckedChangeListener mListener;

    public MainSwitchPreference(Context context) {
        this(context, null);
    }

    public MainSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_main_switch);
        setPersistent(false);
        setIconSpaceReserved(false);
    }

    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            notifyChanged();
        }
    }

    public boolean isChecked() {
        return mChecked;
    }

    public void setCheckedChangeListener(OnCheckedChangeListener listener) {
        mListener = listener;
    }

    /** A live summary (e.g. the active preset) that overrides the XML summary. */
    public void setSummaryText(CharSequence summary) {
        if (TextUtils.equals(mSummaryText, summary)) {
            return;
        }
        mSummaryText = summary;
        notifyChanged();
    }

    public CharSequence getSummaryText() {
        return mSummaryText;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final TextView title = item.findViewById(R.id.switch_title);
        final TextView summary = item.findViewById(R.id.switch_summary);
        final Switch toggle = item.findViewById(R.id.ashfall_switch);
        if (title != null) {
            title.setText(getTitle());
        }
        if (summary != null) {
            final CharSequence text = mSummaryText != null ? mSummaryText : getSummary();
            summary.setText(text);
            summary.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        }
        if (toggle != null) {
            toggle.setChecked(mChecked);
            toggle.setEnabled(isEnabled());
        }
    }

    @Override
    protected void onClick() {
        setChecked(!mChecked);
        if (mListener != null) {
            mListener.onCheckedChanged(this, mChecked);
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        final SavedState state = new SavedState(super.onSaveInstanceState());
        state.checked = mChecked;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(@Nullable Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        final SavedState saved = (SavedState) state;
        super.onRestoreInstanceState(saved.getSuperState());
        mChecked = saved.checked;
        notifyChanged();
    }

    private static final class SavedState extends BaseSavedState {
        boolean checked;

        SavedState(Parcelable superState) {
            super(superState);
        }

        SavedState(Parcel in) {
            super(in);
            checked = in.readInt() != 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(checked ? 1 : 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    @Override
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }
}
