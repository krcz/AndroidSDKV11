package com.acuant.acuantcamera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import com.acuant.acuantcamera.R
import com.acuant.acuantcamera.constant.ACUANT_EXTRA_BORDER_ENABLED
import com.acuant.acuantcamera.constant.ACUANT_EXTRA_IS_AUTO_CAPTURE
import com.acuant.acuantcamera.constant.REQUEST_CAMERA_PERMISSION
import com.acuant.acuantcamera.detector.AcuantDetectorWorker
import com.acuant.acuantcamera.detector.IAcuantDetector
import com.acuant.acuantcamera.helper.*
import com.acuant.acuantcamera.helper.CompareSizesByArea
import com.acuant.acuantcamera.detector.ImageSaveHandler
import com.acuant.acuantcamera.detector.ImageSaver
import com.acuant.acuantcamera.overlay.BaseRectangleView
import com.acuant.acuantcamera.overlay.AcuantOrientationListener
import java.io.File
import java.lang.Exception
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList
import kotlin.math.max

abstract class AcuantBaseCameraFragment : Fragment() {

    enum class CameraState {Align, MoveCloser, Hold, Steady, Capturing, MrzNone, MrzAlign, MrzMoveCloser, MrzReposition, MrzTrying, MrzCapturing, NotInFrame}

    private var captureImageReader: ImageReader? = null
    protected var isProcessing = false
    private var image: Image? = null
    internal var options: AcuantCameraOptions? = null
    internal var isAutoCapture = true
    internal var isBorderEnabled = true
    protected var capturingTextDrawable: Drawable? = null
    protected var defaultTextDrawable: Drawable? = null
    protected lateinit var rectangleView: BaseRectangleView
    protected lateinit var textView: TextView
    protected lateinit var imageView: ImageView
    protected lateinit var detectors: List<IAcuantDetector>
    private lateinit var orientationListener: AcuantOrientationListener
    protected var oldPoints : Array<Point>? = null
    private lateinit var displaySize: Point
    internal var targetSmallDocDpi: Int = 0
    internal var targetLargeDocDpi: Int = 0
    internal var barCodeString: String? = null
    internal var isCapturing = false
    /**
     * This is the output file for our picture.
     */
    internal lateinit var file: File
    /**
     * The current state of camera state for taking pictures.
     *
     * @see .captureCallback
     */
    private var state = STATE_PREVIEW

    /**
     * Approximate time that the camera should spend at each digit.
     *
     * Must be at least 0. If it is set to less than 200 digits might be skipped on slow phones.
     */
    internal var timeInMsPerDigit: Int = 800
    /**
     * The number of digits for the countdown to show until it starts a capture.
     *
     * Must be at least 0. If it is set to less than 2 accidental captures might occur.
     */
    internal var digitsToShow: Int = 2
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null
    /**
     * A [Handler] for running tasks in the background.
     */
    internal var backgroundHandler: Handler? = null
    /**
     * An [ImageReader] that handles still image capture.
     */
    private var imageReader: ImageReader? = null
    /**
     * ID of the current [CameraDevice].
     */
    private lateinit var cameraId: String
    /**
     * An [AutoFitTextureView] for camera preview.
     */
    protected lateinit var textureView: AutoFitTextureView
    /**
     * A [CameraCaptureSession] for camera preview.
     */
    internal var captureSession: CameraCaptureSession? = null
    /**
     * A reference to the opened [CameraDevice].
     */
    internal var cameraDevice: CameraDevice? = null
    /**
     * The [android.util.Size] of camera preview.
     */
    protected lateinit var previewSize: Size
    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    internal lateinit var previewRequestBuilder: CaptureRequest.Builder
    /**
     * [CaptureRequest] generated by [.previewRequestBuilder]
     */
    internal lateinit var previewRequest: CaptureRequest
    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    internal val cameraOpenCloseLock = Semaphore(1)
    /**
     * Whether the current camera device supports Flash or not.
     */
    private var flashSupported = false
    /**
     * Orientation of the camera sensor
     */
    private var sensorOrientation = 0

    private val previewBoundThreshold = 25

    protected abstract fun setTextFromState(state: CameraState)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isAutoCapture = arguments?.getBoolean(ACUANT_EXTRA_IS_AUTO_CAPTURE) ?: true
        isBorderEnabled = arguments?.getBoolean(ACUANT_EXTRA_BORDER_ENABLED) ?: true
    }

    private fun setOptions(options : AcuantCameraOptions?) {
        if(options != null) {
            this.timeInMsPerDigit = options.timeInMsPerDigit
            this.digitsToShow = options.digitsToShow
            isAutoCapture = options.autoCapture
            rectangleView.allowBox = options.allowBox
            rectangleView.bracketLengthInHorizontal = options.bracketLengthInHorizontal
            rectangleView.bracketLengthInVertical = options.bracketLengthInVertical
            rectangleView.defaultBracketMarginHeight = options.defaultBracketMarginHeight
            rectangleView.defaultBracketMarginWidth = options.defaultBracketMarginWidth
            rectangleView.paintColorCapturing = options.colorCapturing
            rectangleView.paintColorHold = options.colorHold
            rectangleView.paintColorBracketAlign = options.colorBracketAlign
            rectangleView.paintColorBracketCapturing = options.colorBracketCapturing
            rectangleView.paintColorBracketCloser = options.colorBracketCloser
            rectangleView.paintColorBracketHold = options.colorBracketHold
            @Suppress("DEPRECATION")
            rectangleView.cardRatio = options.cardRatio
        } else {
            rectangleView.allowBox = isBorderEnabled
        }
    }


    internal fun isDocumentInFrame(points: Array<Point>?) : Boolean{
        if(points != null){
            val startY = 0 //textureView.width.toFloat() / 2 - previewSize.height.toFloat() / 2
            val startX = 0 //textureView.height.toFloat() / 2 - previewSize.width.toFloat() / 2
            val endY = startY + displaySize.x //textureView.width
            val endX = startX + displaySize.y //textureView.height

            for (point in points) {
                if( point.x < -previewBoundThreshold ||
                        point.x > endX + previewBoundThreshold ||
                        (textureView.width - point.y) < -previewBoundThreshold ||
                        (textureView.width - point.y) > endY + previewBoundThreshold) {
                    return false
                }
            }
        }

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        detectors.forEach{
            it.clean()
        }
        image?.close()
    }

    override fun onCreateView(inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera2_basic, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textView = view.findViewById(R.id.acu_display_text)
        imageView = view.findViewById(R.id.acu_help_image)
        orientationListener = AcuantOrientationListener(activity!!.applicationContext, WeakReference(textView), WeakReference(imageView))

        setOptions(options)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        file = File(activity!!.externalCacheDir,  "${UUID.randomUUID()}.jpg")
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        orientationListener.enable()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        rectangleView.end()
        closeCamera()
        stopBackgroundThread()
        orientationListener.disable()
        super.onPause()
    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(fragmentManager!!, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    abstract fun setTapToCapture()

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its state.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@AcuantBaseCameraFragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@AcuantBaseCameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@AcuantBaseCameraFragment.activity?.finish()
        }
    }

    /**
     * This a callback object for the [ImageReader]. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private val onFrameImageAvailableListener = ImageReader.OnImageAvailableListener {
        try {
            val image:Image? = it.acquireLatestImage()
            if (image != null) {
                if(this.isAutoCapture && !this.isProcessing && !this.isCapturing){
                    try{
                        this.isProcessing = true
                        AcuantDetectorWorker(detectors, image).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                    }
                    catch(e:Exception){
                        e.printStackTrace()
                    }
                }
                else{
                    image.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Called only once when the camera deems the id to be properly positioned and
     * is ready to take a photo
     */
    private val onCaptureImageAvailableListener = ImageReader.OnImageAvailableListener {
        try {
            val image:Image? = it.acquireLatestImage()
            if (image != null) {
                if (isCapturing){
                    this.isProcessing = true
                    this.isCapturing = false

                    backgroundHandler?.post(ImageSaver(orientationListener.previousAngle, image, file, object : ImageSaveHandler {
                        override fun onSave() {
                            if (activity is ICameraActivityFinish) {
                                (activity as ICameraActivityFinish).onActivityFinish(file.absolutePath, barCodeString)
                            }
                        }
                    }))
                }
                else{
                    image.close()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> {

                }
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_CONVERGED) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private var focusStateCounter = 0
        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            if (afState == null) {
                if(focusStateCounter < 3){
                    focusStateCounter++
                }
                else{
                    state = STATE_PICTURE_TAKEN
                    captureStillPicture()
                }
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                // CONTROL_AE_STATE can be null on some devices
                runPrecaptureSequence()
            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private fun setUpCameraOutputs(width: Int, height: Int) {
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            setTapToCapture()
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)

                // We don't use a front facing camera in this sample.
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null &&
                        cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue


                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = activity!!.windowManager.defaultDisplay.rotation

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                val swappedDimensions = areDimensionsSwapped(displayRotation)

                displaySize = Point()
                activity!!.windowManager.defaultDisplay.getSize(displaySize)
                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                val maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
                val maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y

                val largestJpeg = Collections.max(
                        listOf(*map.getOutputSizes(ImageFormat.JPEG)),
                        CompareSizesByArea())

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                        rotatedPreviewWidth, rotatedPreviewHeight,
                        maxPreviewWidth, maxPreviewHeight)

                targetSmallDocDpi = (previewSize.width * SMALL_DOC_DPI_SCALE_VALUE).toInt()
                targetLargeDocDpi = (previewSize.width * LARGE_DOC_DPI_SCALE_VALUE).toInt()

                imageReader = ImageReader.newInstance(previewSize.width , previewSize.height,
                        ImageFormat.YUV_420_888, /*maxImages*/ 3).apply {
                    setOnImageAvailableListener(onFrameImageAvailableListener, backgroundHandler)
                }

                captureImageReader = ImageReader.newInstance(largestJpeg.width, largestJpeg.height,
                        ImageFormat.JPEG, /*maxImages*/ 1).apply {
                    setOnImageAvailableListener(onCaptureImageAvailableListener, backgroundHandler)
                }

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setMax(maxPreviewWidth, maxPreviewHeight)
                    textureView.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    textureView.setMax(maxPreviewHeight, maxPreviewWidth)
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }

                // Check if the flash is supported.
                flashSupported =
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                // We've found a viable camera and finished setting up member variables,
                // so we don't need to iterate through other available cameras.
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.acuant_camera_error))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
        }
    }

    /**
     * Determines if the dimensions are swapped given the phone's current rotation.
     *
     * @param displayRotation The current rotation of the display
     *
     * @return true if the dimensions are swapped, false otherwise.
     */
    private fun areDimensionsSwapped(displayRotation: Int): Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270) {
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                if (sensorOrientation == 0 || sensorOrientation == 180) {
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    private fun isPermissionGranted(): Boolean{
        val permission = activity?.let { ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) }
        return permission == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Opens the camera.
     */
    private fun openCamera(width: Int, height: Int) {
        val permission = activity?.let { ContextCompat.checkSelfPermission(it, Manifest.permission.CAMERA) }
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        setUpCameraOutputs(width, height)
        configureTransform(width, height)
        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // Wait for camera to open - 2.5 seconds is sufficient
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            captureImageReader?.close()
            captureImageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Creates a new [CameraCaptureSession] for camera preview.
     */
    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            // This is the output Surface we need to start preview.
            val surface = Surface(texture)

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)
            previewRequestBuilder.addTarget(imageReader!!.surface)

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice?.createCaptureSession(listOf(surface, imageReader?.surface, captureImageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {

                        override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                            // The camera is already closed
                            if (cameraDevice == null) return

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                                previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                previewRequestBuilder.set(CaptureRequest.JPEG_QUALITY, 100)

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build()
                                captureSession?.setRepeatingRequest(previewRequest,
                                        captureCallback, backgroundHandler)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, e.toString())
                            }
                            catch(e:Exception){
                                e.printStackTrace()
                            }

                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            //configuration failed
                        }
                    }, null)


        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        catch(e:Exception){
            e.printStackTrace()
        }
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        if(!isPermissionGranted()) return

        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    internal fun lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START)

            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        catch(e:Exception){
            e.printStackTrace()
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        catch(e:Exception){
            e.printStackTrace()
        }
    }

    /**
     * Capture a still picture. This method should be called when we get a response in
     * [.captureCallback] from both [.lockFocus].
     */
    private fun captureStillPicture() {
        try {
            if (activity == null || cameraDevice == null) return
            activity!!.windowManager.defaultDisplay.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice!!.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(captureImageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.JPEG_QUALITY, 100)

//                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
//                // We have to take that into account and rotate JPEG properly.
//                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
//                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
//                set(CaptureRequest.JPEG_ORIENTATION,
//                        (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

                // Use the same AE and AF modes as the preview.
                set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureFailed(session: CameraCaptureSession,
                                             request: CaptureRequest , failure: CaptureFailure){
                    unlockFocus()
                }
                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult) {
                    unlockFocus()
                }
            }
            captureSession?.apply {
                stopRepeating()
                capture(captureBuilder.build(), captureCallback, null)
            }
        }
        catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        catch(e:Exception){
            e.printStackTrace()
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    internal fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)

            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler)
        }
        catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
        catch(e:Exception){
            e.printStackTrace()
        }
    }

    companion object {

        /**
         * Conversion from screen rotation to JPEG orientation.
         */
        private val ORIENTATIONS = SparseIntArray()
        private const val FRAGMENT_DIALOG = "dialog"

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)

            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        /**
         * Tag for the [Log].
         */
        private const val TAG = "AcuantBaseCameraFrag"

        /**
         * Camera state: Showing camera preview.
         */
        private const val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private const val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private const val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private const val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private const val STATE_PICTURE_TAKEN = 4

        /**
         * Target DPI for preview size 1920x1080 = 350
         * SMALL_DOC_DPI_SCALE_VALUE = target_dpi/preview_size_width
         */
        private const val SMALL_DOC_DPI_SCALE_VALUE = .18229

        /**
         * Target DPI for preview size 1920x1080 = 225
         * LARGE_DOC_DPI_SCALE_VALUE = target_dpi/preview_size_width
         */
        private const val LARGE_DOC_DPI_SCALE_VALUE = .11719

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight ) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            return when {
                bigEnough.size > 0 -> Collections.min(bigEnough, CompareSizesByArea())
                notBigEnough.size > 0 -> Collections.max(notBigEnough, CompareSizesByArea())
                else -> {
                    Log.e(TAG, "Couldn't find any suitable preview size")
                    choices[0]
                }
            }
        }
    }
}


