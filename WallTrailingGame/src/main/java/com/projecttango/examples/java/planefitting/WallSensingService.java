package com.projecttango.examples.java.planefitting;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.opengl.Matrix;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoException;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.projecttango.tangosupport.TangoPointCloudManager;
import com.projecttango.tangosupport.TangoSupport;

import java.nio.FloatBuffer;
import java.util.ArrayList;

import static android.opengl.Matrix.multiplyMV;
import static android.opengl.Matrix.transposeM;


/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 */
public class WallSensingService extends IntentService {

    private static final String TAG = WallSensingService.class.getSimpleName();
    private Tango mTango;
    private TangoConfig mConfig;
    private TangoPointCloudManager mPointCloudManager = new TangoPointCloudManager();
    private double mLastPointCloudTimestamp;
    private int mDisplayRotation = Surface.ROTATION_0;
    private double[] mSavedPlaneModel;

    // amount of difference there can be between parameters in plane model
    // without assuming the current wall is different from the previous wall.
    private double abcMatchThresh = 0.75;
    private double dMatchThresh = 0.2;

    // Threshold used to decide if plane model represents a vertical plane (i.e. a wall)
    private double verticalThreshold = 0.05;

    public WallSensingService() {
        super("WallSensingService");

        // The filter's action is BROADCAST_WALLSENSINGSERVICE_STOP
        IntentFilter statusIntentFilter = new IntentFilter(
                Constants.BROADCAST_WALLSENSINGSERVICE_STOP);

        // Instantiates a new DownloadStateReceiver
        WallSensingService.ShutdownServiceReceiver mShutdownServiceReceiver =
                new WallSensingService.ShutdownServiceReceiver();
        // Registers the DownloadStateReceiver and its intent filters
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mShutdownServiceReceiver,
                statusIntentFilter);
    }

    // Broadcast receiver for receiving shutdown service request from the activity
    private class ShutdownServiceReceiver extends BroadcastReceiver
    {
        // Prevents instantiation
        private ShutdownServiceReceiver() {
        }
        // Called when the BroadcastReceiver gets an Intent it's registered to receive
        @Override
        public void onReceive(Context context, Intent intent) {
        /*
         * Handle Intents here.
         */
            boolean doShutdown = intent.getBooleanExtra(Constants.WALLSENSINGSERVICE_STOP, true);
            if (doShutdown) {
                onDestroy();
            }
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            bindTangoService();
        }
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Use default configuration for Tango Service (motion tracking), plus low latency
        // IMU integration, color camera, depth and drift correction.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // NOTE: Low latency integration is necessary to achieve a precise alignment of
        // virtual objects with the RGB image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        // Drift correction allows motion tracking to recover after it loses tracking.
        // The drift-corrected pose is available through the frame pair with
        // base frame AREA_DESCRIPTION and target frame DEVICE.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DRIFT_CORRECTION, true);

        return config;
    }

    /**
     * Initialize Tango Service as a normal Android Service.
     */
    private void bindTangoService() {
        // Initialize Tango Service as a normal Android Service. Since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time onResume gets called we
        // should create a new Tango object.
        mTango = new Tango(WallSensingService.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready; this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there are no
            // UI thread changes involved.
            @Override
            public void run() {
                // Synchronize against disconnecting while the service is being used in the OpenGL
                // thread or in the UI thread.
                synchronized (WallSensingService.this) {
                    try {
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();
                        TangoSupport.initialize(mTango);
                        Log.e(TAG, "Service has started. Boogaa");
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                    }
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTango.disconnect();
        Log.w(TAG, "WallSensingService shutdown successfully");
    }

    /**
     * Set up the callback listeners for the Tango Service and obtain other parameters required
     * after Tango connection.
     * Listen to updates from the RGB camera and Point Cloud.
     */
    private void startupTango() {
        // No need to add any coordinate frame pairs since we are not
        // using pose data. So just initialize.
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        mTango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData pose) {
                // We are not using OnPoseAvailable for this app.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using camera for the wall sensing service
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(TangoPointCloudData pointCloud) {
                // Save the cloud and point data for later use.
                mPointCloudManager.updatePointCloud(pointCloud);
                findWall();
            }

            @Override
            public void onTangoEvent(TangoEvent event) {
                // We are not using OnPoseAvailable for this app.
            }
        });
    }

    public boolean findWall() {
        // Synchronize against concurrent access to the RGB timestamp in the OpenGL thread
        // and a possible service disconnection due to an onPause event.
        synchronized (this) {

            TangoPointCloudData pointCloud = mPointCloudManager.getLatestPointCloud();
            mLastPointCloudTimestamp = pointCloud.timestamp;

            // Get X, Y, Z of position
            TangoPoseData odomPose = TangoSupport.getPoseAtTime(
                    mLastPointCloudTimestamp,
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoPoseData.COORDINATE_FRAME_DEVICE,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.ROTATION_IGNORED);
            if (odomPose.statusCode != TangoPoseData.POSE_VALID) {
                Log.d(TAG, "Could not get a valid pose from depth camera"
                        + "to odom at time " + mLastPointCloudTimestamp);
                return false;
            }


            // Matrix transforming depth frame to odom frame
            TangoSupport.TangoMatrixTransformData depthTodom = TangoSupport.getMatrixTransformAtTime(
                    mLastPointCloudTimestamp,
                    TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                    TangoSupport.ROTATION_IGNORED);

            // get largest plane
            int mostInliers = 0;
            double[] bestPlaneModelThisTs = null;
            float[] us = {0.5f, 0.4f, 0.6f, 0.2f, 0.8f, 0.0f, 1.0f};
            float[] vs = {0.5f, 0.4f, 0.6f, 0.2f, 0.8f, 0.0f, 1.0f};
            for (float u : us) {
                for (float v : vs) {
                    try {
                        TangoSupport.IntersectionPointPlaneModelPair planeModel = doFitPlane(u, v, mLastPointCloudTimestamp, pointCloud);

                        float[] planeInOdom = transformPlaneNormal(planeModel.planeModel, depthTodom);
                        double[] planeInOdomDouble = {
                                (double) planeInOdom[0],
                                (double) planeInOdom[1],
                                (double) planeInOdom[2],
                                (double) planeInOdom[3],
                        };
//                            Log.w(TAG, String.format("depth X: %f Y: %f Z: %f\n", planeModel.planeModel[0], planeModel.planeModel[1], planeModel.planeModel[2]));
//                            Log.w(TAG, String.format("odom  X: %f Y: %f Z: %f\n", planeInOdom[0], planeInOdom[1], planeInOdom[2]));

                        // Choose walls (not ceilings) + largest plane (the one with most inliers)
                        int nInliers = numInliers(pointCloud.points, planeModel.planeModel);
                        if ((Math.abs(planeInOdom[2]) < verticalThreshold) && (nInliers > mostInliers)) {
                            mostInliers = nInliers;
                            bestPlaneModelThisTs = planeInOdomDouble;
                        }

                    } catch(TangoException t){
                        // Failed to fit plane.
                    } catch(SecurityException t){
                        Toast.makeText(getApplicationContext(),
                                R.string.failed_permissions,
                                Toast.LENGTH_SHORT).show();
                        Log.e(TAG, getString(R.string.failed_permissions), t);
                    }
                }

            }

            // Found good candidate plane during grid search
            if (bestPlaneModelThisTs != null) {

                if (mSavedPlaneModel != null) {
                    boolean match = planesMatch(mSavedPlaneModel, bestPlaneModelThisTs);
                    if (match) {
                        Log.i(TAG, "Matching Wall");
                    }
                    else {
                        Log.i(TAG, "New Wall");
                        double oldWallDist = planeDistance(mSavedPlaneModel, odomPose.translation);
                        double newWallDist = planeDistance(bestPlaneModelThisTs,
                                                           odomPose.translation);
                        // New Wall is closer
                        if (newWallDist < oldWallDist) {
                            mSavedPlaneModel = bestPlaneModelThisTs;
                        }
                    }
                }
                else {
                    mSavedPlaneModel = bestPlaneModelThisTs;
                }
            }

            if (mSavedPlaneModel != null)
            {
                // Save and Publish Distance to Wall
                double newdist = planeDistance(mSavedPlaneModel, odomPose.translation);
                /* Creates a new Intent containing a Uri object
                 * BROADCAST_ACTION is a custom Intent action
                 */
                Intent localIntent =
                        new Intent(Constants.BROADCAST_WALLDISTANCE)
                                // Puts the status into the Intent
                                .putExtra(Constants.WALLDISTANCE, newdist);
                // Broadcasts the Intent to receivers in this app.
                LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
                Log.w(TAG, "Distance to Wall: " + Double.toString(newdist));
            }
            // We still have never seen a plane
            else {
                Toast.makeText(getApplicationContext(),
                        R.string.failed_measurement,
                        Toast.LENGTH_SHORT).show();
                Log.e(TAG, getString(R.string.failed_measurement));
                return false;
            }
        }
        return true;
    }

    /**
     * Use the Tango Support Library with point cloud data to calculate the plane
     * of the world feature pointed at the location the camera is looking.
     * It returns the IntersectionPoint and the PlaneModel as a pair.
     */
    private TangoSupport.IntersectionPointPlaneModelPair doFitPlane(float u, float v, double rgbTimestamp, TangoPointCloudData pointCloud) {

        if (pointCloud == null) {
            return null;
        }

        TangoPoseData depthToColorPose = TangoSupport.getPoseAtTime(
                rgbTimestamp,
                TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                TangoSupport.ROTATION_IGNORED);
        if (depthToColorPose.statusCode != TangoPoseData.POSE_VALID) {
            Log.d(TAG, "Could not get a valid pose from depth camera"
                    + "to color camera at time " + rgbTimestamp);
            return null;
        }

        // Plane model is in depth camera space due to input poses.
        TangoSupport.IntersectionPointPlaneModelPair intersectionPointPlaneModelPair =
                TangoSupport.fitPlaneModelNearPoint(pointCloud,
                        new double[] {0.0, 0.0, 0.0},
                        new double[] {0.0, 0.0, 0.0, 1.0},
                        u, v,
                        mDisplayRotation,
                        depthToColorPose.translation,
                        depthToColorPose.rotation);

        return intersectionPointPlaneModelPair;
    }

    private float[] convertPlaneModelToMatrix(TangoSupport.IntersectionPointPlaneModelPair planeModel) {
        // Note that depth camera's space is:
        // X - right
        // Y - down
        // Z - forward
        float[] up = new float[]{0, 1, 0, 0};
        float[] depthTPlane = matrixFromPointNormalUp(
                planeModel.intersectionPoint,
                planeModel.planeModel,
                up);
        return depthTPlane;
    }

    /**
     * Calculates a transformation matrix based on a point, a normal and the up gravity vector.
     * The coordinate frame of the target transformation will be a right handed system with Z+ in
     * the direction of the normal and Y+ up.
     */
    private float[] matrixFromPointNormalUp(double[] point, double[] normal, float[] up) {
        float[] zAxis = new float[]{(float) normal[0], (float) normal[1], (float) normal[2]};
        normalize(zAxis);
        float[] xAxis = crossProduct(up, zAxis);
        normalize(xAxis);
        float[] yAxis = crossProduct(zAxis, xAxis);
        normalize(yAxis);
        float[] m = new float[16];
        Matrix.setIdentityM(m, 0);
        m[0] = xAxis[0];
        m[1] = xAxis[1];
        m[2] = xAxis[2];
        m[4] = yAxis[0];
        m[5] = yAxis[1];
        m[6] = yAxis[2];
        m[8] = zAxis[0];
        m[9] = zAxis[1];
        m[10] = zAxis[2];
        m[12] = (float) point[0];
        m[13] = (float) point[1];
        m[14] = (float) point[2];
        return m;
    }

    /**
     * Normalize a vector.
     */
    private void normalize(float[] v) {
        double norm = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= norm;
        v[1] /= norm;
        v[2] /= norm;
    }

    /**
     * Cross product between two vectors following the right-hand rule.
     */
    private float[] crossProduct(float[] v1, float[] v2) {
        float[] result = new float[3];
        result[0] = v1[1] * v2[2] - v2[1] * v1[2];
        result[1] = v1[2] * v2[0] - v2[2] * v1[0];
        result[2] = v1[0] * v2[1] - v2[0] * v1[1];
        return result;
    }

    /**
     * Finds the distance to a plane model in the odom reference frame to the phone's position
     * the Equation used is abs(a*x + b*y + c*z + d) / sqrt(a^2 + b^2 + c^2)
     * where a, b, c, and d are the plane_model in the form (ax+by+cz+d = 0)
     * and x, y, and z are the position of the phone
     */
    private double planeDistance(double[] normal, double[] xyz) {
        double result = (Math.abs(normal[0]*xyz[0] + normal[1]*xyz[1] + normal[2]*xyz[2] + normal[3]) /
                Math.sqrt(Math.pow(normal[0], 2) + Math.pow(normal[1], 2) + Math.pow(normal[2], 2)));
        return result;
    }

    /**
     * calculates p_twiddle = T_transpose * p
     * where T_tranpose is the transpose of the transform matrix from depth to odom
     * and p is [a,b,c,d] plane params, in the depth camera frame.
     */
    private float[] transformPlaneNormal(
            double[] depthNormal,
            TangoSupport.TangoMatrixTransformData depthTodom) {

        float[] depthNormalF = {
                (float) depthNormal[0],
                (float) depthNormal[1],
                (float) depthNormal[2],
                (float) depthNormal[3]
        };

        float[] depthTodomTranspose = new float[16];
        transposeM(depthTodomTranspose, 0, depthTodom.matrix, 0);

        float[] odomNormal = new float[4];
        multiplyMV(odomNormal, 0, depthTodomTranspose, 0, depthNormalF, 0);

        return odomNormal;
    }

    private int numInliers(FloatBuffer points, double[] normal) {
        int count = 0;
        int idx = 0;
        float[] quad = new float[4];
        while (points.hasRemaining()) {
            quad[idx] = points.get();
            idx++;

            // filled a quadruplet
            if (idx > 3) {
                // calculate if inlier
                double planeEq = normal[0]*quad[0] + normal[1]*quad[1] + normal[2]*quad[2] + normal[3];
                if (Math.abs(planeEq) < 0.001) {
                    count++;
                }
                idx = 0;
            }
        }
        return count;
    }

    private boolean planesMatch(double[] savedPlaneModel, double[] planeModel) {

        return ((Math.abs(savedPlaneModel[0] - planeModel[0]) < abcMatchThresh) &&
                (Math.abs(savedPlaneModel[1] - planeModel[1]) < abcMatchThresh) &&
                (Math.abs(savedPlaneModel[2] - planeModel[2]) < abcMatchThresh) &&
                (Math.abs(savedPlaneModel[2] - planeModel[2]) < dMatchThresh));
    }

    /*
     * TODO: implement PassedPlane function to find walls that set deeper than current walls
     *             -->
     * Good __________
     *                \__ --> ___________
     * vs
     *             -->    -->
     * Bad  __________
     *                \__________________
     */
}
