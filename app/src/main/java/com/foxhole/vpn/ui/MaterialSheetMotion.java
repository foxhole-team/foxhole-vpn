package com.foxhole.vpn.ui;

import android.annotation.SuppressLint;
import androidx.compose.animation.core.FiniteAnimationSpec;
import androidx.compose.material3.MotionScheme;
import androidx.compose.material3.SheetState;

@SuppressLint("UnsafeOptInUsageError")
final class MaterialSheetMotion {
    private MaterialSheetMotion() {
    }

    static FiniteAnimationSpec<Float> fastSpatialSpec() {
        return MotionScheme.Companion.standard$material3().fastSpatialSpec();
    }

    static void applyFastSpatialSpec(
            SheetState sheetState,
            FiniteAnimationSpec<Float> motionSpec
    ) {
        sheetState.setShowMotionSpec$material3(motionSpec);
        sheetState.setHideMotionSpec$material3(motionSpec);
    }
}
