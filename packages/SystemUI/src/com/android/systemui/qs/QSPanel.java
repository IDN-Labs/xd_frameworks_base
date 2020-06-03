/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.systemui.qs.tileimpl.QSTileImpl.getColorForState;
import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;
import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.service.quicksettings.Tile;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHierarchyManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.QSHost.Callback;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSliderView;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController.BrightnessMirrorListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/** View that represents the quick settings tile panel (when expanded/pulled down). **/
public class QSPanel extends LinearLayout implements Tunable, Callback, BrightnessMirrorListener,
        Dumpable {

    public static final String QS_SHOW_BRIGHTNESS = "qs_show_brightness";
    public static final String QS_SHOW_HEADER = "qs_show_header";

    private static final String TAG = "QSPanel";

    protected final Context mContext;
    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    private final BroadcastDispatcher mBroadcastDispatcher;
    protected final MediaHost mMediaHost;
    private String mCachedSpecs = "";
    protected final View mBrightnessView;
    private final H mHandler = new H();
    private final MetricsLogger mMetricsLogger = Dependency.get(MetricsLogger.class);
    private final QSTileRevealController mQsTileRevealController;
    /** Whether or not the QS media player feature is enabled. */
    protected boolean mUsingMediaPlayer;

    protected boolean mExpanded;
    protected boolean mListening;

    private QSDetail.Callback mCallback;
    private BrightnessController mBrightnessController;
    private final DumpManager mDumpManager;
    private final QSLogger mQSLogger;
    protected final UiEventLogger mUiEventLogger;
    protected QSTileHost mHost;

    protected QSSecurityFooter mFooter;
    private PageIndicator mFooterPageIndicator;
    private boolean mGridContentVisible = true;
    private int mContentMarginStart;
    private int mContentMarginEnd;
    private int mVisualTilePadding;

    protected QSTileLayout mTileLayout;

    private QSCustomizer mCustomizePanel;
    private Record mDetailRecord;

    private BrightnessMirrorController mBrightnessMirrorController;
    private View mDivider;

    @Inject
    public QSPanel(
            @Named(VIEW_CONTEXT) Context context,
            AttributeSet attrs,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            QSLogger qsLogger,
            MediaHost mediaHost,
            UiEventLogger uiEventLogger
    ) {
        super(context, attrs);
        mUsingMediaPlayer = useQsMediaPlayer(context);
        mMediaHost = mediaHost;
        mContext = context;
        mQSLogger = qsLogger;
        mDumpManager = dumpManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mUiEventLogger = uiEventLogger;

        setOrientation(VERTICAL);

        mBrightnessView = LayoutInflater.from(mContext).inflate(
            R.layout.quick_settings_brightness_dialog, this, false);
        addView(mBrightnessView);

        mTileLayout = (QSTileLayout) LayoutInflater.from(mContext).inflate(
                R.layout.qs_paged_tile_layout, this, false);
        mQSLogger.logAllTilesChangeListening(mListening, getDumpableTag(), mCachedSpecs);
        mTileLayout.setListening(mListening);
        addView((View) mTileLayout);

        mQsTileRevealController = new QSTileRevealController(mContext, this,
                (PagedTileLayout) mTileLayout);

        addDivider();

        mFooter = new QSSecurityFooter(this, context);
        addView(mFooter.getView());

        updateResources();

        mBrightnessController = new BrightnessController(getContext(),
                findViewById(R.id.brightness_slider), mBroadcastDispatcher);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        // Add media carousel at the end
        if (useQsMediaPlayer(getContext())) {
            addMediaHostView();
        }
    }

    protected void addMediaHostView() {
        mMediaHost.setExpansion(1.0f);
        mMediaHost.setShowsOnlyActiveMedia(false);
        mMediaHost.init(MediaHierarchyManager.LOCATION_QS);
        ViewGroup hostView = mMediaHost.getHostView();
        addView(hostView);
        int bottomPadding = getResources().getDimensionPixelSize(
                R.dimen.quick_settings_expanded_bottom_margin);
        MarginLayoutParams layoutParams = (MarginLayoutParams) hostView.getLayoutParams();
        layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.bottomMargin = bottomPadding;
        hostView.setLayoutParams(layoutParams);
        updateMediaHostContentMargins();
    }

    protected void addDivider() {
        mDivider = LayoutInflater.from(mContext).inflate(R.layout.qs_divider, this, false);
        mDivider.setBackgroundColor(Utils.applyAlpha(mDivider.getAlpha(),
                getColorForState(mContext, Tile.STATE_ACTIVE)));
        addView(mDivider);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // We want all the logic of LinearLayout#onMeasure, and for it to assign the excess space
        // not used by the other children to PagedTileLayout. However, in this case, LinearLayout
        // assumes that PagedTileLayout would use all the excess space. This is not the case as
        // PagedTileLayout height is quantized (because it shows a certain number of rows).
        // Therefore, after everything is measured, we need to make sure that we add up the correct
        // total height
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = getPaddingBottom() + getPaddingTop();
        int numChildren = getChildCount();
        for (int i = 0; i < numChildren; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                height += child.getMeasuredHeight();
                MarginLayoutParams layoutParams = (MarginLayoutParams) child.getLayoutParams();
                height += layoutParams.topMargin + layoutParams.bottomMargin;
            }
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    public View getDivider() {
        return mDivider;
    }

    public QSTileRevealController getQsTileRevealController() {
        return mQsTileRevealController;
    }

    public boolean isShowingCustomize() {
        return mCustomizePanel != null && mCustomizePanel.isCustomizing();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, QS_SHOW_BRIGHTNESS);

        if (mHost != null) {
            setTiles(mHost.getTiles());
        }
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(this);
        }
        mDumpManager.registerDumpable(getDumpableTag(), this);
    }

    @Override
    protected void onDetachedFromWindow() {
        Dependency.get(TunerService.class).removeTunable(this);
        if (mHost != null) {
            mHost.removeCallback(this);
        }
        if (mTileLayout != null) {
            mTileLayout.setListening(false);
        }
        for (TileRecord record : mRecords) {
            record.tile.removeCallbacks();
        }
        mRecords.clear();
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(this);
        }
        mDumpManager.unregisterDumpable(getDumpableTag());
        super.onDetachedFromWindow();
    }

    protected String getDumpableTag() {
        return TAG;
    }

    @Override
    public void onTilesChanged() {
        setTiles(mHost.getTiles());
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (QS_SHOW_BRIGHTNESS.equals(key)) {
            updateViewVisibilityForTuningValue(mBrightnessView, newValue);
        }
    }

    private void updateViewVisibilityForTuningValue(View view, @Nullable String newValue) {
        view.setVisibility(TunerService.parseIntegerSwitch(newValue, true) ? VISIBLE : GONE);
    }

    public void openDetails(String subPanel) {
        QSTile tile = getTile(subPanel);
        // If there's no tile with that name (as defined in QSFactoryImpl or other QSFactory),
        // QSFactory will not be able to create a tile and getTile will return null
        if (tile != null) {
            showDetailAdapter(true, tile.getDetailAdapter(), new int[]{getWidth() / 2, 0});
        }
    }

    private QSTile getTile(String subPanel) {
        for (int i = 0; i < mRecords.size(); i++) {
            if (subPanel.equals(mRecords.get(i).tile.getTileSpec())) {
                return mRecords.get(i).tile;
            }
        }
        return mHost.createTile(subPanel);
    }

    public void setBrightnessMirror(BrightnessMirrorController c) {
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(this);
        }
        mBrightnessMirrorController = c;
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(this);
        }
        updateBrightnessMirror();
    }

    @Override
    public void onBrightnessMirrorReinflated(View brightnessMirror) {
        updateBrightnessMirror();
    }

    View getBrightnessView() {
        return mBrightnessView;
    }

    public void setCallback(QSDetail.Callback callback) {
        mCallback = callback;
    }

    public void setHost(QSTileHost host, QSCustomizer customizer) {
        mHost = host;
        mHost.addCallback(this);
        setTiles(mHost.getTiles());
        mFooter.setHostEnvironment(host);
        mCustomizePanel = customizer;
        if (mCustomizePanel != null) {
            mCustomizePanel.setHost(mHost);
        }
    }

    /**
     * Links the footer's page indicator, which is used in landscape orientation to save space.
     *
     * @param pageIndicator indicator to use for page scrolling
     */
    public void setFooterPageIndicator(PageIndicator pageIndicator) {
        if (mTileLayout instanceof PagedTileLayout) {
            mFooterPageIndicator = pageIndicator;
            updatePageIndicator();
        }
    }

    private void updatePageIndicator() {
        if (mTileLayout instanceof PagedTileLayout) {
            if (mFooterPageIndicator != null) {
                mFooterPageIndicator.setVisibility(View.GONE);

                ((PagedTileLayout) mTileLayout).setPageIndicator(mFooterPageIndicator);
            }
        }
    }

    public QSTileHost getHost() {
        return mHost;
    }

    public void updateResources() {
        int tileSize = getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
        int tileBg = getResources().getDimensionPixelSize(R.dimen.qs_tile_background_size);
        mVisualTilePadding = (int) ((tileSize - tileBg) / 2.0f);
        updatePadding();

        updatePageIndicator();

        if (mListening) {
            refreshAllTiles();
        }
        if (mTileLayout != null) {
            mTileLayout.updateResources();
        }
    }

    protected void updatePadding() {
        final Resources res = mContext.getResources();
        setPaddingRelative(getPaddingStart(),
                res.getDimensionPixelSize(R.dimen.qs_panel_padding_top),
                getPaddingEnd(),
                res.getDimensionPixelSize(R.dimen.qs_panel_padding_bottom));
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mFooter.onConfigurationChanged();
        updateResources();

        updateBrightnessMirror();
    }

    public void updateBrightnessMirror() {
        if (mBrightnessMirrorController != null) {
            ToggleSliderView brightnessSlider = findViewById(R.id.brightness_slider);
            ToggleSliderView mirrorSlider = mBrightnessMirrorController.getMirror()
                    .findViewById(R.id.brightness_slider);
            brightnessSlider.setMirror(mirrorSlider);
            brightnessSlider.setMirrorController(mBrightnessMirrorController);
        }
    }

    public void onCollapse() {
        if (mCustomizePanel != null && mCustomizePanel.isShown()) {
            mCustomizePanel.hide();
        }
    }

    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mQSLogger.logPanelExpanded(expanded, getDumpableTag());
        mExpanded = expanded;
        if (!mExpanded && mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setCurrentItem(0, false);
        }
        mMetricsLogger.visibility(MetricsEvent.QS_PANEL, mExpanded);
        if (!mExpanded) {
            mUiEventLogger.log(closePanelEvent());
            closeDetail();
        } else {
            mUiEventLogger.log(openPanelEvent());
            logTiles();
        }
    }

    public void setPageListener(final PagedTileLayout.PageListener pageListener) {
        if (mTileLayout instanceof PagedTileLayout) {
            ((PagedTileLayout) mTileLayout).setPageListener(pageListener);
        }
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (mTileLayout != null) {
            mQSLogger.logAllTilesChangeListening(listening, getDumpableTag(), mCachedSpecs);
            mTileLayout.setListening(listening);
        }
        if (mListening) {
            refreshAllTiles();
        }
    }

    private String getTilesSpecs() {
        return mRecords.stream()
                .map(tileRecord ->  tileRecord.tile.getTileSpec())
                .collect(Collectors.joining(","));
    }

    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);
        getFooter().setListening(listening);
        // Set the listening as soon as the QS fragment starts listening regardless of the expansion,
        // so it will update the current brightness before the slider is visible.
        setBrightnessListening(listening);
    }

    public void setBrightnessListening(boolean listening) {
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        for (TileRecord r : mRecords) {
            r.tile.refreshState();
        }
        mFooter.refreshState();
    }

    public void showDetailAdapter(boolean show, DetailAdapter adapter, int[] locationInWindow) {
        int xInWindow = locationInWindow[0];
        int yInWindow = locationInWindow[1];
        ((View) getParent()).getLocationInWindow(locationInWindow);

        Record r = new Record();
        r.detailAdapter = adapter;
        r.x = xInWindow - locationInWindow[0];
        r.y = yInWindow - locationInWindow[1];

        locationInWindow[0] = xInWindow;
        locationInWindow[1] = yInWindow;

        showDetail(show, r);
    }

    protected void showDetail(boolean show, Record r) {
        mHandler.obtainMessage(H.SHOW_DETAIL, show ? 1 : 0, 0, r).sendToTarget();
    }

    public void setTiles(Collection<QSTile> tiles) {
        setTiles(tiles, false);
    }

    public void setTiles(Collection<QSTile> tiles, boolean collapsedView) {
        if (!collapsedView) {
            mQsTileRevealController.updateRevealedTiles(tiles);
        }
        for (TileRecord record : mRecords) {
            mTileLayout.removeTile(record);
            record.tile.removeCallback(record.callback);
        }
        mRecords.clear();
        mCachedSpecs = "";
        for (QSTile tile : tiles) {
            addTile(tile, collapsedView);
        }
    }

    protected void drawTile(TileRecord r, QSTile.State state) {
        r.tileView.onStateChanged(state);
    }

    protected QSTileView createTileView(QSTile tile, boolean collapsedView) {
        return mHost.createTileView(tile, collapsedView);
    }

    protected QSEvent openPanelEvent() {
        return QSEvent.QS_PANEL_EXPANDED;
    }

    protected QSEvent closePanelEvent() {
        return QSEvent.QS_PANEL_COLLAPSED;
    }

    protected QSEvent tileVisibleEvent() {
        return QSEvent.QS_TILE_VISIBLE;
    }

    protected boolean shouldShowDetail() {
        return mExpanded;
    }

    protected TileRecord addTile(final QSTile tile, boolean collapsedView) {
        final TileRecord r = new TileRecord();
        r.tile = tile;
        r.tileView = createTileView(tile, collapsedView);
        final QSTile.Callback callback = new QSTile.Callback() {
            @Override
            public void onStateChanged(QSTile.State state) {
                drawTile(r, state);
            }

            @Override
            public void onShowDetail(boolean show) {
                // Both the collapsed and full QS panels get this callback, this check determines
                // which one should handle showing the detail.
                if (shouldShowDetail()) {
                    QSPanel.this.showDetail(show, r);
                }
            }

            @Override
            public void onToggleStateChanged(boolean state) {
                if (mDetailRecord == r) {
                    fireToggleStateChanged(state);
                }
            }

            @Override
            public void onScanStateChanged(boolean state) {
                r.scanState = state;
                if (mDetailRecord == r) {
                    fireScanStateChanged(r.scanState);
                }
            }

            @Override
            public void onAnnouncementRequested(CharSequence announcement) {
                if (announcement != null) {
                    mHandler.obtainMessage(H.ANNOUNCE_FOR_ACCESSIBILITY, announcement)
                            .sendToTarget();
                }
            }
        };
        r.tile.addCallback(callback);
        r.callback = callback;
        r.tileView.init(r.tile);
        r.tile.refreshState();
        mRecords.add(r);
        mCachedSpecs = getTilesSpecs();

        if (mTileLayout != null) {
            mTileLayout.addTile(r);
        }

        return r;
    }


    public void showEdit(final View v) {
        v.post(new Runnable() {
            @Override
            public void run() {
                if (mCustomizePanel != null) {
                    if (!mCustomizePanel.isCustomizing()) {
                        int[] loc = v.getLocationOnScreen();
                        int x = loc[0] + v.getWidth() / 2;
                        int y = loc[1] + v.getHeight() / 2;
                        mCustomizePanel.show(x, y);
                    }
                }

            }
        });
    }

    public void closeDetail() {
        if (mCustomizePanel != null && mCustomizePanel.isShown()) {
            // Treat this as a detail panel for now, to make things easy.
            mCustomizePanel.hide();
            return;
        }
        showDetail(false, mDetailRecord);
    }

    public int getGridHeight() {
        return getMeasuredHeight();
    }

    protected void handleShowDetail(Record r, boolean show) {
        if (r instanceof TileRecord) {
            handleShowDetailTile((TileRecord) r, show);
        } else {
            int x = 0;
            int y = 0;
            if (r != null) {
                x = r.x;
                y = r.y;
            }
            handleShowDetailImpl(r, show, x, y);
        }
    }

    private void handleShowDetailTile(TileRecord r, boolean show) {
        if ((mDetailRecord != null) == show && mDetailRecord == r) return;

        if (show) {
            r.detailAdapter = r.tile.getDetailAdapter();
            if (r.detailAdapter == null) return;
        }
        r.tile.setDetailListening(show);
        int x = r.tileView.getLeft() + r.tileView.getWidth() / 2;
        int y = r.tileView.getDetailY() + mTileLayout.getOffsetTop(r) + getTop();
        handleShowDetailImpl(r, show, x, y);
    }

    private void handleShowDetailImpl(Record r, boolean show, int x, int y) {
        setDetailRecord(show ? r : null);
        fireShowingDetail(show ? r.detailAdapter : null, x, y);
    }

    protected void setDetailRecord(Record r) {
        if (r == mDetailRecord) return;
        mDetailRecord = r;
        final boolean scanState = mDetailRecord instanceof TileRecord
                && ((TileRecord) mDetailRecord).scanState;
        fireScanStateChanged(scanState);
    }

    void setGridContentVisibility(boolean visible) {
        int newVis = visible ? VISIBLE : INVISIBLE;
        setVisibility(newVis);
        if (mGridContentVisible != visible) {
            mMetricsLogger.visibility(MetricsEvent.QS_PANEL, newVis);
        }
        mGridContentVisible = visible;
    }

    private void logTiles() {
        for (int i = 0; i < mRecords.size(); i++) {
            QSTile tile = mRecords.get(i).tile;
            mMetricsLogger.write(tile.populate(new LogMaker(tile.getMetricsCategory())
                    .setType(MetricsEvent.TYPE_OPEN)));
        }
    }

    private void fireShowingDetail(DetailAdapter detail, int x, int y) {
        if (mCallback != null) {
            mCallback.onShowingDetail(detail, x, y);
        }
    }

    private void fireToggleStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onToggleStateChanged(state);
        }
    }

    private void fireScanStateChanged(boolean state) {
        if (mCallback != null) {
            mCallback.onScanStateChanged(state);
        }
    }

    public void clickTile(ComponentName tile) {
        final String spec = CustomTile.toSpec(tile);
        final int N = mRecords.size();
        for (int i = 0; i < N; i++) {
            if (mRecords.get(i).tile.getTileSpec().equals(spec)) {
                mRecords.get(i).tile.click();
                break;
            }
        }
    }

    QSTileLayout getTileLayout() {
        return mTileLayout;
    }

    QSTileView getTileView(QSTile tile) {
        for (TileRecord r : mRecords) {
            if (r.tile == tile) {
                return r.tileView;
            }
        }
        return null;
    }

    public QSSecurityFooter getFooter() {
        return mFooter;
    }

    public void showDeviceMonitoringDialog() {
        mFooter.showDeviceMonitoringDialog();
    }

    public void setContentMargins(int startMargin, int endMargin) {
        // Only some views actually want this content padding, others want to go all the way
        // to the edge like the brightness slider
        mContentMarginStart = startMargin;
        mContentMarginEnd = endMargin;
        updateTileLayoutMargins(mContentMarginStart - mVisualTilePadding,
                mContentMarginEnd - mVisualTilePadding);
        updateMediaHostContentMargins();
    }

    /**
     * Update the margins of all tile Layouts.
     *
     * @param visualMarginStart the visual start margin of the tile, adjusted for local insets
     *                          to the tile. This can be set on a tileLayout
     * @param visualMarginEnd the visual end margin of the tile, adjusted for local insets
     *                        to the tile. This can be set on a tileLayout
     */
    protected void updateTileLayoutMargins(int visualMarginStart, int visualMarginEnd) {
        updateMargins((View) mTileLayout, visualMarginStart, visualMarginEnd);
    }

    /**
     * Update the margins of the media hosts
     */
    protected void updateMediaHostContentMargins() {
        if (mUsingMediaPlayer && mMediaHost != null) {
            updateMargins(mMediaHost.getHostView(), mContentMarginStart, mContentMarginEnd);
        }
    }

    /**
     * Update the margins of a view.
     *
     * @param view the view to adjust
     * @param start the start margin to set
     * @param end the end margin to set
     */
    protected void updateMargins(View view, int start, int end) {
        LayoutParams lp = (LayoutParams) view.getLayoutParams();
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        view.setLayoutParams(lp);
    }

    public MediaHost getMediaHost() {
        return mMediaHost;
    }

    private class H extends Handler {
        private static final int SHOW_DETAIL = 1;
        private static final int SET_TILE_VISIBILITY = 2;
        private static final int ANNOUNCE_FOR_ACCESSIBILITY = 3;

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == SHOW_DETAIL) {
                handleShowDetail((Record) msg.obj, msg.arg1 != 0);
            } else if (msg.what == ANNOUNCE_FOR_ACCESSIBILITY) {
                announceForAccessibility((CharSequence) msg.obj);
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(getClass().getSimpleName() + ":");
        pw.println("  Tile records:");
        for (TileRecord record : mRecords) {
            if (record.tile instanceof Dumpable) {
                pw.print("    "); ((Dumpable) record.tile).dump(fd, pw, args);
                pw.print("    "); pw.println(record.tileView.toString());
            }
        }
    }

    protected static class Record {
        DetailAdapter detailAdapter;
        int x;
        int y;
    }

    public static final class TileRecord extends Record {
        public QSTile tile;
        public com.android.systemui.plugins.qs.QSTileView tileView;
        public boolean scanState;
        public QSTile.Callback callback;
    }

    public interface QSTileLayout {

        default void saveInstanceState(Bundle outState) {}

        default void restoreInstanceState(Bundle savedInstanceState) {}

        void addTile(TileRecord tile);

        void removeTile(TileRecord tile);

        int getOffsetTop(TileRecord tile);

        boolean updateResources();

        void setListening(boolean listening);

        default void setExpansion(float expansion) {}

        int getNumVisibleTiles();
    }
}
