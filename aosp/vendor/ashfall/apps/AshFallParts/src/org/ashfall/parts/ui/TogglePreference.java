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
 * A normal Settings switch row whose state is owned by the caller.
 *
 * <p>The row never flips itself: activating it asks the listener for the
 * desired state, and a confirmation dialog decides whether {@link #setChecked}
 * is called.  That is how the dangerous throttling override is gated.  Uses
 * the framework {@link Switch} like {@link MainSwitchPreference}, so it
 * inflates under {@code Theme.SubSettingsBase.Expressive}.
 */
public class TogglePreference extends Preference {

    public interface OnToggleRequested {
        void onToggleRequested(TogglePreference preference, boolean desired);
    }

    private boolean mChecked;
    private CharSequence mSummaryText;
    private OnToggleRequested mListener;

    public TogglePreference(Context context) {
        this(context, null);
    }

    public TogglePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_toggle);
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

    public void setOnToggleRequestedListener(OnToggleRequested listener) {
        mListener = listener;
    }

    public void setSummaryText(CharSequence summary) {
        if (TextUtils.equals(mSummaryText, summary)) {
            return;
        }
        mSummaryText = summary;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View item = holder.itemView;
        final TextView title = item.findViewById(R.id.toggle_title);
        final TextView summary = item.findViewById(R.id.toggle_summary);
        final Switch toggle = item.findViewById(R.id.toggle_switch);
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
        if (mListener != null) {
            mListener.onToggleRequested(this, !mChecked);
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
