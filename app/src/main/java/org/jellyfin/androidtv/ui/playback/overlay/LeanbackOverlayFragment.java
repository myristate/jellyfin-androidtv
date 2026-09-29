package org.jellyfin.androidtv.ui.playback.overlay;

import static org.koin.java.KoinJavaComponent.inject;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.app.PlaybackSupportFragment;
import androidx.leanback.widget.BaseGridView;

import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.UserSettingPreferences;
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment;
import org.jellyfin.androidtv.ui.playback.PlaybackController;
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer;
import org.jellyfin.sdk.api.client.ApiClient;

import coil3.ImageLoader;
import kotlin.Lazy;
import timber.log.Timber;

public class LeanbackOverlayFragment extends PlaybackSupportFragment {
    private CustomPlaybackTransportControlGlue playerGlue;
    private VideoPlayerAdapter playerAdapter;
    private boolean shouldShowOverlay = true;
    private Lazy<PlaybackControllerContainer> playbackControllerContainer = inject(PlaybackControllerContainer.class);
    private final Lazy<UserSettingPreferences> userSettingPreferences = inject(UserSettingPreferences.class);
    private Lazy<ImageLoader> imageLoader = inject(ImageLoader.class);
    private Lazy<ApiClient> api = inject(ApiClient.class);
    private Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setBackgroundType(BG_LIGHT);

        PlaybackController playbackController = playbackControllerContainer.getValue().getPlaybackController();
        if (playbackController == null) {
            Timber.w("PlaybackController is null, skipping initialization.");
            return;
        }

        playerAdapter = new VideoPlayerAdapter(playbackController, this);
        playerGlue = new CustomPlaybackTransportControlGlue(getContext(), playerAdapter, playbackController);
        playerGlue.setHost(new CustomPlaybackFragmentGlueHost(this));
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        super.hideControlsOverlay(false);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Leanback only shows the controls for key presses, a tap or mouse click on the video shows them too.
        // While the controls are showing, taps reach the buttons and the seek bar as usual and keep them from fading.
        // This replaces the listener leanback sets in its own onResume.
        View grid = requireView().findViewById(androidx.leanback.R.id.container_list);
        if (grid instanceof BaseGridView) {
            ((BaseGridView) grid).setOnTouchInterceptListener(event -> {
                if (isControlsOverlayVisible()) {
                    tickle();
                    return false;
                }
                CustomPlaybackOverlayFragment master = playerAdapter != null ? playerAdapter.getMasterOverlayFragment() : null;
                if (event.getActionMasked() == MotionEvent.ACTION_UP && master != null && master.canShowControlsFromTouch()) {
                    // Key presses allow the overlay before showing it, see CustomPlaybackOverlayFragment.keyListener
                    setShouldShowOverlay(true);
                    showControlsOverlay(true);
                }
                return true;
            });
        }
    }

    public void initFromView(CustomPlaybackOverlayFragment customPlaybackOverlayFragment) {
        playerGlue.setInitialPlaybackDrawable();
        playerAdapter.setMasterOverlayFragment(customPlaybackOverlayFragment);
    }

    @Override
    public void showControlsOverlay(boolean runAnimation) {
        if (shouldShowOverlay) {
            super.showControlsOverlay(runAnimation);
            playerAdapter.getMasterOverlayFragment().show();
        }
    }

    @Override
    public void hideControlsOverlay(boolean runAnimation) {
        super.hideControlsOverlay(runAnimation);
        playerAdapter.getMasterOverlayFragment().hide();
    }

    public void updateCurrentPosition() {
        playerAdapter.updateCurrentPosition();
        updatePlayState();
    }

    public void updatePlayState() {
        playerAdapter.updatePlayState();
        playerGlue.updatePlayState();
    }

    public void setShouldShowOverlay(boolean shouldShowOverlay) {
        this.shouldShowOverlay = shouldShowOverlay;
    }

    public void hideOverlay() {
        hideControlsOverlay(true);
    }

    public void setFading(boolean fadingEnabled) {
        playerAdapter.getMasterOverlayFragment().setFadingEnabled(fadingEnabled);
    }

    public void mediaInfoChanged() {
        org.jellyfin.sdk.model.api.BaseItemDto currentlyPlayingItem = playbackControllerContainer.getValue().getPlaybackController().getCurrentlyPlayingItem();
        if (currentlyPlayingItem == null) return;

        playerGlue.invalidatePlaybackControls();
        playerGlue.setSeekEnabled(playerAdapter.canSeek());

        long skipForwardLength = userSettingPreferences.getValue().get(UserSettingPreferences.Companion.getSkipForwardLength()).longValue();
        playerGlue.setSeekProvider(playerAdapter.canSeek() ? new CustomSeekProvider(playerAdapter, imageLoader.getValue(), api.getValue(), requireContext(), skipForwardLength) : null);
        recordingStateChanged();
        playerAdapter.updateDuration();
    }

    public void recordingStateChanged() {
        playerGlue.recordingStateChanged();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (playerAdapter != null) {
            playerAdapter.getMasterOverlayFragment().onPause();
        }
    }

    public CustomPlaybackTransportControlGlue getPlayerGlue() {
        return playerGlue;
    }

    public void onFullyInitialized() {
        updatePlayState();
        playerGlue.addMediaActions();
    }
}
