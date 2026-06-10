package com.miniobs.app;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ViewFlipper;
import com.bumptech.glide.Glide;
import java.util.ArrayList;
import java.util.List;

public class SceneManager {

    public enum SceneType {
        STARTING_SOON,
        GAMEPLAY,
        BRB
    }

    public interface SceneChangeListener {
        void onSceneChanged(SceneType newScene);
    }

    private final Context context;
    private SceneType currentScene = SceneType.STARTING_SOON;
    private final List<SceneChangeListener> listeners = new ArrayList<>();
    private ViewFlipper viewFlipper;
    private View startingSoonView;
    private View gameplayView;
    private View brbView;

    public SceneManager(Context context) {
        this.context = context;
    }

    public void attachViews(ViewFlipper flipper,
                            View startingSoon,
                            View gameplay,
                            View brb) {
        this.viewFlipper = flipper;
        this.startingSoonView = startingSoon;
        this.gameplayView = gameplay;
        this.brbView = brb;
        applyScene(currentScene, false);
    }

    public void switchScene(SceneType scene) {
        if (scene == currentScene) return;
        currentScene = scene;
        applyScene(scene, true);
        for (SceneChangeListener l : listeners) l.onSceneChanged(scene);
    }

    private void applyScene(SceneType scene, boolean animate) {
        if (viewFlipper == null) return;
        if (animate) {
            viewFlipper.setInAnimation(context, android.R.anim.slide_in_left);
            viewFlipper.setOutAnimation(context, android.R.anim.slide_out_right);
        }
        switch (scene) {
            case STARTING_SOON:
                viewFlipper.setDisplayedChild(0);
                break;
            case GAMEPLAY:
                viewFlipper.setDisplayedChild(1);
                break;
            case BRB:
                viewFlipper.setDisplayedChild(2);
                break;
        }
    }

    public void setStartingSoonImage(ImageView imageView, String path) {
        if (path != null && !path.isEmpty()) {
            Glide.with(context).load(path).centerCrop().into(imageView);
        }
    }

    public void setBrbImage(ImageView imageView, String path) {
        if (path != null && !path.isEmpty()) {
            Glide.with(context).load(path).centerCrop().into(imageView);
        }
    }

    public SceneType getCurrentScene() { return currentScene; }

    public void addListener(SceneChangeListener l) { listeners.add(l); }
    public void removeListener(SceneChangeListener l) { listeners.remove(l); }

    public String getSceneLabel(SceneType type) {
        switch (type) {
            case STARTING_SOON: return "Starting Soon";
            case GAMEPLAY:      return "Gameplay";
            case BRB:           return "Be Right Back";
            default:            return "Unknown";
        }
    }
}
