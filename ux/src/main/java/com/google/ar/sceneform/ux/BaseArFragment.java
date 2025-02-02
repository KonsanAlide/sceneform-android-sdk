/*
 * Copyright 2018 Google LLC. All Rights Reserved.
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
 */
package com.google.ar.sceneform.ux;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnWindowFocusChangeListener;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.collision.Ray;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The AR fragment brings in the required view layout and controllers for common AR features.
 */
public abstract class BaseArFragment extends Fragment
        implements Scene.OnPeekTouchListener, Scene.OnUpdateListener {
    private static final String TAG = BaseArFragment.class.getSimpleName();

    /**
     * Invoked when the ARCore Session is initialized.
     */
    public interface OnSessionInitializationListener {
        /**
         * The callback will only be invoked once after a Session is initialized and before it is
         * resumed for the first time.
         *
         * @param session The ARCore Session.
         * @see #setOnSessionInitializationListener(OnSessionInitializationListener)
         */
        void onSessionInitialization(Session session);
    }

    /**
     * Invoked when the ARCore Session is to be configured.
     */
    public interface OnSessionConfigurationListener {
        /**
         * The callback will only be invoked once after a Session is initialized and before it is
         * resumed for the first time.
         *
         * @param session The ARCore Session.
         * @param config The ARCore Session Config.
         *
         * @see #setOnSessionConfigurationListener(OnSessionConfigurationListener)
         */
        void onSessionConfiguration(Session session, Config config);
    }

    /**
     * Invoked when an ARCore plane is tapped.
     */
    public interface OnTapArPlaneListener {
        /**
         * Called when an ARCore plane is tapped. The callback will only be invoked if no {@link
         * com.google.ar.sceneform.Node} was tapped.
         *
         * @param hitResult   The ARCore hit result that occurred when tapping the plane
         * @param plane       The ARCore Plane that was tapped
         * @param motionEvent the motion event that triggered the tap
         * @see #setOnTapArPlaneListener(OnTapArPlaneListener)
         */
        void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent);
    }

    /**
     * Invoked when a single tap occur.
     */
    public interface OnSingleArTapListener {
        /**
         * Called when an ARCore anchor is tapped.
         *
         * @param pose   The ARCore hit pose
         * @param motionEvent the motion event that triggered the tap
         */
        void onSingleTap(HitTestResult hitResult,Pose pose, MotionEvent motionEvent);

        /**
         * Called when nothing is tapped.
         * @param ray  calculates a ray in world space going from the near-plane of the camera and going through a
         *    * point in screen space. Screen space is in Android device screen coordinates: TopLeft = (0, 0)
         *    * BottomRight = (Screen Width, Screen Height) The device coordinate space is unaffected by the
         *    * orientation of the device.
         * @param motionEvent the motion event that triggered the tap
         */
        void onSingleTapNothing(HitTestResult hitResult,Ray ray,MotionEvent motionEvent);

        /**
         * Called when an ARCore node is tapped.
         *
         * @param node   The hit ARCore node
         * @param pos   The hit position of the ARCore node
         * @param motionEvent the motion event that triggered the tap
         */
        void onSingleTapOnNode(Node node, Vector3 pos, MotionEvent motionEvent);
    }

    /**
     * Invoked when an ARCore plane is found.
     */
    public interface OnArPlaneFoundListener {
        /**
         * Called when an ARCore plane is found.
         *
         * @param plane       The ARCore Plane that was found
         * @see #setOnArPlaneFoundListener(OnArPlaneFoundListener)
         */
        void onPlaneFound(Plane plane);
    }

    /**
     * Invoked when a double tap occur.
     */
    public interface OnDoubleArTapListener {
        /**
         * Called when a double tap occur on the AR fragment.
         *
         * @param node        The node is tapped, if null, nothing is tapped
         * @param hitResult   The ARCore hit test result
         * @param motionEvent the motion event that triggered the tap
         */
        void onDoubleTap(Node node,HitTestResult hitResult,MotionEvent motionEvent);
    }


    /**
     * The key for the fullscreen argument
     */
    public static final String ARGUMENT_FULLSCREEN = "fullscreen";

    private static final int RC_PERMISSIONS = 1010;
    private boolean installRequested;
    private boolean sessionInitializationFailed = false;
    private ArSceneView arSceneView;
    private PlaneDiscoveryController planeDiscoveryController;
    private TransformationSystem transformationSystem;
    private GestureDetector gestureDetector;
    private FrameLayout frameLayout;
    private boolean isStarted;
    private boolean canRequestDangerousPermissions = true;
    private boolean fullscreen = true;
    @Nullable
    private OnSessionInitializationListener onSessionInitializationListener;
    @Nullable
    private OnSessionConfigurationListener onSessionConfigurationListener;
    @Nullable
    private OnTapArPlaneListener onTapArPlaneListener;
    @Nullable
    private OnSingleArTapListener  onSingleTapListener;

    private Node selectedNode = null;
    private HitTestResult currentHitResult = null;

    @Nullable
    private OnArPlaneFoundListener onArPlaneFoundListener=null;

    @Nullable
    private OnDoubleArTapListener onDoubleTapListener=null;


    @SuppressWarnings({"initialization"})
    private final OnWindowFocusChangeListener onFocusListener =
            (hasFocus -> onWindowFocusChanged(hasFocus));

    /**
     * Gets the ArSceneView for this fragment.
     */
    public ArSceneView getArSceneView() {
        return arSceneView;
    }

    /**
     * Gets the plane discovery controller, which displays instructions for how to scan for planes.
     */
    public PlaneDiscoveryController getPlaneDiscoveryController() {
        return planeDiscoveryController;
    }

    /**
     * Gets the transformation system, which is used by {@link TransformableNode} for detecting
     * gestures and coordinating which node is selected.
     */
    public TransformationSystem getTransformationSystem() {
        return transformationSystem;
    }

    /**
     * Registers a callback to be invoked when the ARCore Session is initialized. The callback will
     * only be invoked once after the Session is initialized and before it is resumed.
     *
     * @param onSessionInitializationListener the {@link OnSessionInitializationListener} to attach.
     */
    public void setOnSessionInitializationListener(
            @Nullable OnSessionInitializationListener onSessionInitializationListener) {
        this.onSessionInitializationListener = onSessionInitializationListener;
    }

    /**
     * Registers a callback to be invoked when the ARCore Session is to configured. The callback will
     * only be invoked once after the Session default config has been applied and before it is
     * configured on the Session.
     *
     * @param onSessionConfigurationListener the {@link OnSessionConfigurationListener} to attach.
     */
    public void setOnSessionConfigurationListener(
            @Nullable OnSessionConfigurationListener onSessionConfigurationListener) {
        this.onSessionConfigurationListener = onSessionConfigurationListener;
    }

    /**
     * Registers a callback to be invoked when an ARCore Plane is tapped. The callback will only be
     * invoked if no {@link com.google.ar.sceneform.Node} was tapped.
     *
     * @param onTapArPlaneListener the {@link OnTapArPlaneListener} to attach
     */
    public void setOnTapArPlaneListener(@Nullable OnTapArPlaneListener onTapArPlaneListener) {
        this.onTapArPlaneListener = onTapArPlaneListener;
    }

    @Override
    @SuppressWarnings({"initialization"})
    // Suppress @UnderInitialization warning.
    public View onCreateView(
            LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        frameLayout =
                (FrameLayout) inflater.inflate(R.layout.sceneform_ux_fragment_layout, container, false);
        arSceneView = (ArSceneView) frameLayout.findViewById(R.id.sceneform_ar_scene_view);

        // Setup the instructions view.
        View instructionsView = loadPlaneDiscoveryView(inflater, container);
        if (instructionsView != null) {
            frameLayout.addView(instructionsView);
        }
        planeDiscoveryController = new PlaneDiscoveryController(instructionsView);

        if (Build.VERSION.SDK_INT < VERSION_CODES.N) {
            // Enforce API level 24
            return frameLayout;
        }

        transformationSystem = makeTransformationSystem();

        gestureDetector =
                new GestureDetector(
                        getContext(),
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapUp(MotionEvent e) {
                                onSingleTap(e);
                                return true;
                            }

                            @Override
                            public boolean onDoubleTapEvent(MotionEvent e) {
                                if(onDoubleTapListener!=null) {
                                    onDoubleTapListener.onDoubleTap(selectedNode,currentHitResult,e);
                                }
                                return true;
                            }


                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }
                        });

        arSceneView.getScene().addOnPeekTouchListener(this);
        arSceneView.getScene().addOnUpdateListener(this);

        if (isArRequired()) {
            // Request permissions
            requestDangerousPermissions();
        }

        // Make the app immersive and don't turn off the display.
        arSceneView.getViewTreeObserver().addOnWindowFocusChangeListener(onFocusListener);
        return frameLayout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle arguments = getArguments();

        if (arguments != null) {
            fullscreen = arguments.getBoolean(ARGUMENT_FULLSCREEN, true);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        arSceneView.getViewTreeObserver().removeOnWindowFocusChangeListener(onFocusListener);
    }

    /**
     * Returns true if this application is AR Required, false if AR Optional. This is called when
     * initializing the application and the session.
     */
    public abstract boolean isArRequired();

    /**
     * Returns an array of dangerous permissions that are required by the app in addition to
     * Manifest.permission.CAMERA, which is needed by ARCore. If no additional permissions are needed,
     * an empty array should be returned.
     */
    public abstract String[] getAdditionalPermissions();

    /**
     * Starts the process of requesting dangerous permissions. This combines the CAMERA permission
     * required of ARCore and any permissions returned from getAdditionalPermissions(). There is no
     * specific processing on the result of the request, subclasses can override
     * onRequestPermissionsResult() if additional processing is needed.
     *
     * <p>{@link #setCanRequestDangerousPermissions(Boolean)} can stop this function from doing
     * anything.
     */
    protected void requestDangerousPermissions() {
        if (!canRequestDangerousPermissions) {
            // If this is in progress, don't do it again.
            return;
        }
        canRequestDangerousPermissions = false;

        List<String> permissions = new ArrayList<String>();
        String[] additionalPermissions = getAdditionalPermissions();
        int permissionLength = additionalPermissions != null ? additionalPermissions.length : 0;
        for (int i = 0; i < permissionLength; ++i) {
            if (ActivityCompat.checkSelfPermission(requireActivity(), additionalPermissions[i])
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(additionalPermissions[i]);
            }
        }

        //TODO : Use ARCore CameraPermissionHelper.requestCameraPermission(this); instead

        // Always check for camera permission
        if (ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (!permissions.isEmpty()) {
            // Request the permissions
            requestPermissions(permissions.toArray(new String[permissions.size()]), RC_PERMISSIONS);
        }
    }

    /**
     * Receives the results for permission requests.
     *
     * <p>Brings up a dialog to request permissions. The dialog can send the user to the Settings app,
     * or finish the activity.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        AlertDialog.Builder builder;
        builder =
                new AlertDialog.Builder(requireActivity(), android.R.style.Theme_Material_Dialog_Alert);

        builder
                .setTitle("Camera permission required")
                .setMessage("Add camera permission via Settings?")
                .setPositiveButton(
                        android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // If Ok was hit, bring up the Settings app.
                                Intent intent = new Intent();
                                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.fromParts("package", requireActivity().getPackageName(), null));
                                requireActivity().startActivity(intent);
                                // When the user closes the Settings app, allow the app to resume.
                                // Allow the app to ask for permissions again now.
                                setCanRequestDangerousPermissions(true);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setOnDismissListener(
                        new OnDismissListener() {
                            @Override
                            public void onDismiss(final DialogInterface arg0) {
                                // canRequestDangerousPermissions will be true if "OK" was selected from the dialog,
                                // false otherwise.  If "OK" was selected do nothing on dismiss, the app will
                                // continue and may ask for permission again if needed.
                                // If anything else happened, finish the activity when this dialog is
                                // dismissed.
                                if (!getCanRequestDangerousPermissions()) {
                                    requireActivity().finish();
                                }
                            }
                        })
                .show();
    }

    /**
     * If true, {@link #requestDangerousPermissions()} returns without doing anything, if false
     * permissions will be requested
     */
    protected Boolean getCanRequestDangerousPermissions() {
        return canRequestDangerousPermissions;
    }

    /**
     * If true, {@link #requestDangerousPermissions()} returns without doing anything, if false
     * permissions will be requested
     */
    protected void setCanRequestDangerousPermissions(Boolean canRequestDangerousPermissions) {
        this.canRequestDangerousPermissions = canRequestDangerousPermissions;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (isArRequired() && arSceneView.getSession() == null) {
            initializeSession();
        }
        start();
    }

    protected final boolean requestInstall() throws UnavailableException {
        switch (ArCoreApk.getInstance().requestInstall(requireActivity(), !installRequested)) {
            case INSTALL_REQUESTED:
                installRequested = true;
                return true;
            case INSTALLED:
                break;
        }
        return false;
    }

    /**
     * Initializes the ARCore session. The CAMERA permission is checked before checking the
     * installation state of ARCore. Once the permissions and installation are OK, the method
     * #getSessionConfiguration(Session session) is called to get the session configuration to use.
     * Sceneform requires that the ARCore session be updated using LATEST_CAMERA_IMAGE to avoid
     * blocking while drawing. This mode is set on the configuration object returned from the
     * subclass.
     */
    protected final void initializeSession() {

        // Only try once
        if (sessionInitializationFailed) {
            return;
        }
        // if we have the camera permission, create the session
        if (ContextCompat.checkSelfPermission(requireActivity(), "android.permission.CAMERA")
                == PackageManager.PERMISSION_GRANTED) {

            UnavailableException sessionException = null;
            try {
                if (requestInstall()) {
                    return;
                }

                Session session = createSession();

                if (this.onSessionInitializationListener != null) {
                    this.onSessionInitializationListener.onSessionInitialization(session);
                }

                Config config = getSessionConfiguration(session);
                config.setDepthMode(Config.DepthMode.DISABLED);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                // Force the non-blocking mode for the session.
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);

                if (this.onSessionConfigurationListener != null) {
                    this.onSessionConfigurationListener.onSessionConfiguration(session, config);
                }

                session.configure(config);
                getArSceneView().setupSession(session);
                return;
            } catch (UnavailableException e) {
                sessionException = e;
            } catch (Exception e) {
                sessionException = new UnavailableException();
                sessionException.initCause(e);
            }
            sessionInitializationFailed = true;
            handleSessionException(sessionException);

        } else {
            requestDangerousPermissions();
        }
    }

    private Session createSession()
            throws UnavailableSdkTooOldException, UnavailableDeviceNotCompatibleException,
            UnavailableArcoreNotInstalledException, UnavailableApkTooOldException {
        Session session = createSessionWithFeatures();
        if (session == null) {
            session = new Session(requireActivity());
        }
        return session;
    }

    /**
     * Creates the ARCore Session with the with features defined in #getSessionFeatures. If this
     * returns null, the Session will be created with the default features.
     */

    protected @Nullable
    Session createSessionWithFeatures()
            throws UnavailableSdkTooOldException, UnavailableDeviceNotCompatibleException,
            UnavailableArcoreNotInstalledException, UnavailableApkTooOldException {
        return new Session(requireActivity(), getSessionFeatures());
    }

    /**
     * Creates the transformation system used by this fragment. Can be overridden to create a custom
     * transformation system.
     */
    protected TransformationSystem makeTransformationSystem() {
        FootprintSelectionVisualizer selectionVisualizer = new FootprintSelectionVisualizer();

        TransformationSystem transformationSystem =
                new TransformationSystem(getResources().getDisplayMetrics(), selectionVisualizer);

        setupSelectionRenderable(selectionVisualizer);

        return transformationSystem;
    }

    @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
    protected void setupSelectionRenderable(FootprintSelectionVisualizer selectionVisualizer) {
        ModelRenderable.builder()
                .setSource(getActivity(), R.raw.sceneform_footprint)
                .setIsFilamentGltf(true)
                .build()
                .thenAccept(
                        renderable -> {
                            // If the selection visualizer already has a footprint renderable, then it was set to
                            // something custom. Don't override the custom visual.
                            if (selectionVisualizer.getFootprintRenderable() == null) {
                                selectionVisualizer.setFootprintRenderable(renderable);
                            }
                        })
                .exceptionally(
                        throwable -> {
                            Toast toast =
                                    Toast.makeText(
                                            getContext(), "Unable to load footprint renderable", Toast.LENGTH_LONG);
                            toast.setGravity(Gravity.CENTER, 0, 0);
                            toast.show();
                            return null;
                        });
    }

    protected abstract void handleSessionException(UnavailableException sessionException);

    protected abstract Config getSessionConfiguration(Session session);

    /**
     * Specifies additional features for creating an ARCore {@link com.google.ar.core.Session}. See
     * {@link com.google.ar.core.Session.Feature}.
     */

    protected abstract Set<Session.Feature> getSessionFeatures();

    protected void onWindowFocusChanged(boolean hasFocus) {
        FragmentActivity activity = getActivity();
        if (hasFocus && activity != null) {
            if (fullscreen) {
                // This flag should be set before using the WindowInsetsController
                // otherwise showing the transparent bars by swipe doesn't work
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowInsetsController controller = activity.getWindow().getInsetsController();

                    if (controller != null) {
                        controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } else {
                    // Standard Android full-screen functionality.
                    activity
                            .getWindow()
                            .getDecorView()
                            .setSystemUiVisibility(
                                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stop();
    }

    @Override
    public void onDestroy() {
        stop();
        arSceneView.destroy();
        super.onDestroy();
    }

    @Override
    public void onPeekTouch(HitTestResult hitTestResult, MotionEvent motionEvent) {
        transformationSystem.onTouch(hitTestResult, motionEvent);

        currentHitResult = hitTestResult;
        selectedNode = hitTestResult.getNode();
        /*
        if(selectedNode!=null && (selectedNode instanceof BaseTransformableNode)) {
            transformationSystem.selectNode((BaseTransformableNode)selectedNode);
        }
         */
        gestureDetector.onTouchEvent(motionEvent);
    }

    @Override
    public void onUpdate(FrameTime frameTime) {
        Frame frame = arSceneView.getArFrame();
        if (frame == null) {
            return;
        }

        for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING) {
                planeDiscoveryController.hide();
                if(onArPlaneFoundListener!=null) {
                    onArPlaneFoundListener.onPlaneFound(plane);
                }
            }
        }
    }

    private void start() {
        if (isStarted) {
            return;
        }

        if (getActivity() != null) {
            isStarted = true;
            try {
                arSceneView.resume();
            } catch (CameraNotAvailableException ex) {
                sessionInitializationFailed = true;
            }
            if (!sessionInitializationFailed) {
                planeDiscoveryController.show();
            }
        }
    }

    private void stop() {
        if (!isStarted) {
            return;
        }

        isStarted = false;
        planeDiscoveryController.hide();
        arSceneView.pause();
    }

    // Load the default view we use for the plane discovery instructions.
    @Nullable
    private View loadPlaneDiscoveryView(LayoutInflater inflater, @Nullable ViewGroup container) {
        return inflater.inflate(R.layout.sceneform_plane_discovery_layout, container, false);
    }

    private void onSingleTap(MotionEvent motionEvent) {
        if(selectedNode!=null) {
            Vector3 hitPos = currentHitResult.getPoint();
            if (onSingleTapListener != null && currentHitResult.getPoint() != null) {
                if(selectedNode instanceof BaseTransformableNode)
                    transformationSystem.selectNode((BaseTransformableNode)selectedNode);
                onSingleTapListener.onSingleTapOnNode(selectedNode, hitPos, motionEvent);
                selectedNode.receiveSingleTap(currentHitResult,motionEvent);
            }
            return ;
        }

        Frame frame = arSceneView.getArFrame();
        transformationSystem.selectNode(null);

        // Local variable for nullness static-analysis.
        OnTapArPlaneListener onTapArPlaneListener = this.onTapArPlaneListener;

        if (frame != null && onTapArPlaneListener != null) {
            if (motionEvent != null && frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
                Pose hitPos = null;
                for (HitResult hit : frame.hitTest(motionEvent)) {
                    Trackable trackable = hit.getTrackable();
                    if (trackable instanceof Plane && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
                        Plane plane = (Plane) trackable;
                        onTapArPlaneListener.onTapPlane(hit, plane, motionEvent);
                        hitPos = hit.getHitPose();
                        break;
                    } else {
                        hitPos = hit.getHitPose();
                    }
                }

                if (onSingleTapListener != null) {
                    if(hitPos != null) {
                        onSingleTapListener.onSingleTap(currentHitResult, hitPos, motionEvent);
                    }else {
                        int index = motionEvent.getActionIndex();
                        Ray ray = arSceneView.getScene().getCamera().screenPointToRay(motionEvent.getX(index), motionEvent.getY(index));
                        onSingleTapListener.onSingleTapNothing(currentHitResult, ray, motionEvent);
                    }
                }
            }
        } else {
            if (frame != null && motionEvent != null && onSingleTapListener != null) {
                int index = motionEvent.getActionIndex();
                Ray ray = arSceneView.getScene().getCamera().screenPointToRay(motionEvent.getX(index), motionEvent.getY(index));
                onSingleTapListener.onSingleTapNothing(currentHitResult, ray, motionEvent);
            }
        }
    }


    public void setOnArPlaneFoundListener(OnArPlaneFoundListener listener) {
        this.onArPlaneFoundListener = listener;
    }

    public void setOnDoubleTapListener(@Nullable OnDoubleArTapListener onDoubleTapListener) {
        this.onDoubleTapListener = onDoubleTapListener;
    }

    public void setOnSingleTapListener(@Nullable OnSingleArTapListener onSingleTapListener) {
        this.onSingleTapListener = onSingleTapListener;
    }
}