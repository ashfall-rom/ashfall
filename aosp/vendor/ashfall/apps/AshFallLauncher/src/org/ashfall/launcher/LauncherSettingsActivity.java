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

package org.ashfall.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Launcher appearance settings, reachable from the bottom-left Settings button. */
public final class LauncherSettingsActivity extends Activity {
    private static final int REQUEST_IMAGE = 4001;

    private LauncherBackgroundView backgroundView;
    private LinearLayout sections;
    private TextView doneButton;

    private TextView solidType;
    private TextView gradientType;
    private TextView imageType;
    private LinearLayout solidBox;
    private LinearLayout gradientBox;
    private LinearLayout imageBox;

    private TextView directionDown;
    private TextView directionRight;
    private Switch animateSwitch;

    private TextView chooseImage;
    private TextView clearImage;
    private TextView imageName;
    private TextView dimLabel;
    private SeekBar dimSlider;

    private final List<View> optionButtons = new ArrayList<>();
    private final List<View> actionButtons = new ArrayList<>();
    private final List<ColorSwatchView> allSwatches = new ArrayList<>();
    private final List<ColorSwatchView> solidSwatches = new ArrayList<>();
    private final List<ColorSwatchView> gradientStartSwatches = new ArrayList<>();
    private final List<ColorSwatchView> gradientEndSwatches = new ArrayList<>();
    private final List<ColorSwatchView> accentSwatches = new ArrayList<>();

    private int accent = Appearance.DEFAULT_ACCENT;

    private interface ColorListener {
        void onColor(int color);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher_settings);

        accent = Appearance.load(this).accent;
        backgroundView = findViewById(R.id.settings_background);
        sections = findViewById(R.id.settings_sections);
        doneButton = findViewById(R.id.settings_done);

        buildUi();

        doneButton.setOnClickListener(view -> finish());
        actionButtons.add(doneButton);

        applyAccent();
        updateBackgroundTypeUi();
        updateImageUi();
        previewBackground();

        sections.post(() -> solidType.requestFocus());
    }

    @Override
    protected void onResume() {
        super.onResume();
        previewBackground();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers do not offer a persistable grant; keep the URI anyway.
        }
        Appearance.setImageUri(this, uri.toString());
        Appearance.setBackgroundType(this, Appearance.BACKGROUND_IMAGE);
        updateBackgroundTypeUi();
        updateImageUi();
        previewBackground();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B
                && event.getAction() == KeyEvent.ACTION_UP) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void buildUi() {
        addSectionTitle(getString(R.string.background));

        LinearLayout types = row();
        solidType = typeButton(getString(R.string.background_solid),
                () -> setBackgroundType(Appearance.BACKGROUND_SOLID));
        gradientType = typeButton(getString(R.string.background_gradient),
                () -> setBackgroundType(Appearance.BACKGROUND_GRADIENT));
        imageType = typeButton(getString(R.string.background_image),
                () -> setBackgroundType(Appearance.BACKGROUND_IMAGE));
        types.addView(solidType, weightParams(1, 0));
        types.addView(gradientType, weightParams(1, 8));
        types.addView(imageType, weightParams(1, 0));
        sections.addView(types);

        solidBox = new LinearLayout(this);
        solidBox.setOrientation(LinearLayout.VERTICAL);
        solidBox.addView(sectionLabel(getString(R.string.background_colour)));
        addSwatchRows(solidBox, Appearance.PALETTE, solidSwatches, color -> {
            Appearance.setSolidColor(this, color);
            markSelected(solidSwatches, color);
            previewBackground();
        });
        sections.addView(solidBox);

        gradientBox = new LinearLayout(this);
        gradientBox.setOrientation(LinearLayout.VERTICAL);
        gradientBox.addView(sectionLabel(getString(R.string.gradient_start)));
        addSwatchRows(gradientBox, Appearance.PALETTE, gradientStartSwatches, color -> {
            Appearance.setGradientStart(this, color);
            markSelected(gradientStartSwatches, color);
            previewBackground();
        });
        gradientBox.addView(sectionLabel(getString(R.string.gradient_end)));
        addSwatchRows(gradientBox, Appearance.PALETTE, gradientEndSwatches, color -> {
            Appearance.setGradientEnd(this, color);
            markSelected(gradientEndSwatches, color);
            previewBackground();
        });
        gradientBox.addView(sectionLabel(getString(R.string.gradient_direction)));

        LinearLayout directions = row();
        directionDown = Ui.optionButton(this, getString(R.string.direction_down));
        directionDown.setOnClickListener(view -> setGradientDirection(
                Appearance.DIRECTION_VERTICAL));
        directionRight = Ui.optionButton(this, getString(R.string.direction_right));
        directionRight.setOnClickListener(view -> setGradientDirection(
                Appearance.DIRECTION_HORIZONTAL));
        optionButtons.add(directionDown);
        optionButtons.add(directionRight);
        directions.addView(directionDown, weightParams(1, 0));
        directions.addView(directionRight, weightParams(1, 8));
        gradientBox.addView(directions);

        LinearLayout animateRow = row();
        animateRow.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView animateLabel = new TextView(this);
        animateLabel.setText(R.string.animate_colours);
        animateLabel.setTextColor(getColor(R.color.text_primary));
        animateLabel.setTextSize(14f);
        animateRow.addView(animateLabel, weightParams(1, 0));
        animateSwitch = new Switch(this);
        animateRow.addView(animateSwitch);
        animateSwitch.setOnCheckedChangeListener((button, checked) -> {
            Appearance.setGradientAnimate(this, checked);
            previewBackground();
        });
        gradientBox.addView(animateRow);
        sections.addView(gradientBox);

        imageBox = new LinearLayout(this);
        imageBox.setOrientation(LinearLayout.VERTICAL);
        imageName = new TextView(this);
        imageName.setTextColor(getColor(R.color.text_secondary));
        imageName.setTextSize(13f);
        imageName.setSingleLine(true);
        imageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        imageName.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        imageBox.addView(imageName);

        LinearLayout imageButtons = row();
        chooseImage = Ui.optionButton(this, getString(R.string.choose_image));
        chooseImage.setOnClickListener(view -> chooseImage());
        clearImage = Ui.optionButton(this, getString(R.string.clear_image));
        clearImage.setOnClickListener(view -> clearImage());
        actionButtons.add(chooseImage);
        actionButtons.add(clearImage);
        imageButtons.addView(chooseImage, weightParams(1, 0));
        imageButtons.addView(clearImage, weightParams(1, 8));
        imageBox.addView(imageButtons);

        dimLabel = new TextView(this);
        dimLabel.setTextColor(getColor(R.color.text_secondary));
        dimLabel.setTextSize(13f);
        dimLabel.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        imageBox.addView(dimLabel);

        dimSlider = new SeekBar(this);
        dimSlider.setMax(80);
        dimSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    Appearance.setImageDim(LauncherSettingsActivity.this, progress);
                    updateDimLabel(progress);
                    previewBackground();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        imageBox.addView(dimSlider);
        sections.addView(imageBox);

        addSectionTitle(getString(R.string.accent_colour));
        addSwatchRows(sections, Appearance.ACCENTS, accentSwatches, color -> {
            Appearance.setAccent(this, color);
            accent = color;
            markSelected(accentSwatches, color);
            applyAccent();
        });
    }

    private void addSectionTitle(String text) {
        sections.addView(Ui.sectionTitle(this, text));
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12f);
        label.setTextColor(getColor(R.color.text_muted));
        label.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        return label;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView typeButton(String text, Runnable action) {
        TextView button = Ui.optionButton(this, text);
        button.setOnClickListener(view -> action.run());
        optionButtons.add(button);
        return button;
    }

    private void addSwatchRows(ViewGroup parent, int[] colors,
            List<ColorSwatchView> target, ColorListener listener) {
        LinearLayout current = null;
        for (int i = 0; i < colors.length; i++) {
            if (i % 7 == 0) {
                current = row();
                current.setPadding(0, Ui.dp(this, 2), 0, 0);
                parent.addView(current);
            }
            ColorSwatchView swatch = new ColorSwatchView(this);
            swatch.setColor(colors[i]);
            swatch.setAccent(accent);
            swatch.setSelected(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    Ui.dp(this, 42), Ui.dp(this, 42));
            params.setMargins(0, 0, Ui.dp(this, 8), 0);
            swatch.setOnClickListener(view -> listener.onColor(swatch.getColor()));
            target.add(swatch);
            allSwatches.add(swatch);
            current.addView(swatch, params);
        }
    }

    private void setBackgroundType(int type) {
        Appearance.setBackgroundType(this, type);
        updateBackgroundTypeUi();
        previewBackground();
    }

    private void setGradientDirection(int direction) {
        Appearance.setGradientDirection(this, direction);
        updateBackgroundTypeUi();
        previewBackground();
    }

    private void updateBackgroundTypeUi() {
        Appearance appearance = Appearance.load(this);

        solidType.setSelected(appearance.backgroundType == Appearance.BACKGROUND_SOLID);
        gradientType.setSelected(appearance.backgroundType == Appearance.BACKGROUND_GRADIENT);
        imageType.setSelected(appearance.backgroundType == Appearance.BACKGROUND_IMAGE);
        solidBox.setVisibility(appearance.backgroundType == Appearance.BACKGROUND_SOLID
                ? View.VISIBLE : View.GONE);
        gradientBox.setVisibility(appearance.backgroundType == Appearance.BACKGROUND_GRADIENT
                ? View.VISIBLE : View.GONE);
        imageBox.setVisibility(appearance.backgroundType == Appearance.BACKGROUND_IMAGE
                ? View.VISIBLE : View.GONE);

        directionDown.setSelected(
                appearance.gradientDirection == Appearance.DIRECTION_VERTICAL);
        directionRight.setSelected(
                appearance.gradientDirection == Appearance.DIRECTION_HORIZONTAL);
        animateSwitch.setChecked(appearance.gradientAnimate);

        markSelected(solidSwatches, appearance.solidColor);
        markSelected(gradientStartSwatches, appearance.gradientStart);
        markSelected(gradientEndSwatches, appearance.gradientEnd);
        markSelected(accentSwatches, appearance.accent);
    }

    private void updateImageUi() {
        Appearance appearance = Appearance.load(this);
        String uri = appearance.imageUri;
        if (uri == null) {
            imageName.setText(R.string.no_image);
            clearImage.setEnabled(false);
            clearImage.setAlpha(0.45f);
        } else {
            String name = Uri.parse(uri).getLastPathSegment();
            imageName.setText(name == null ? uri : name);
            clearImage.setEnabled(true);
            clearImage.setAlpha(1f);
        }
        dimSlider.setProgress(appearance.imageDim);
        updateDimLabel(appearance.imageDim);
    }

    private void updateDimLabel(int dim) {
        dimLabel.setText(getString(R.string.image_dim, dim));
    }

    private void markSelected(List<ColorSwatchView> swatches, int color) {
        for (ColorSwatchView swatch : swatches) {
            swatch.setSelected(swatch.getColor() == color);
        }
    }

    private void applyAccent() {
        for (View view : optionButtons) {
            Ui.applyOptionBackground(view, accent);
        }
        for (View view : actionButtons) {
            Ui.applyActionBackground(view, accent);
        }
        for (ColorSwatchView swatch : allSwatches) {
            swatch.setAccent(accent);
        }

        ColorStateList accentState = ColorStateList.valueOf(accent);
        dimSlider.setProgressTintList(accentState);
        dimSlider.setThumbTintList(accentState);
        animateSwitch.setThumbTintList(accentState);
        animateSwitch.setTrackTintList(ColorStateList.valueOf(
                Appearance.withAlpha(accent, 0x66)));
        animateSwitch.setTextColor(getColor(R.color.text_primary));
    }

    private void previewBackground() {
        backgroundView.apply(Appearance.load(this));
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_IMAGE);
        } catch (android.content.ActivityNotFoundException ignored) {
            // No document picker on this build.
        }
    }

    private void clearImage() {
        Appearance.setImageUri(this, null);
        Appearance.setBackgroundType(this, Appearance.BACKGROUND_SOLID);
        updateBackgroundTypeUi();
        updateImageUi();
        previewBackground();
    }

    private LinearLayout.LayoutParams weightParams(int weight, int endMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(0, 0, Ui.dp(this, endMarginDp), 0);
        return params;
    }
}
