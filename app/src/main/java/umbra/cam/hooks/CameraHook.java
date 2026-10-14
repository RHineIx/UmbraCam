package umbra.cam.hooks;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import io.github.libxposed.api.XposedModuleInterface;

import umbra.cam.xposed.UmbraCamModule;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hooks for the original Camera API (android.hardware.Camera). Consolidated all Legacy Camera logic
 * including Viewfinder Surface/SurfaceTexture diversion and PreviewCallback injection.
 */
@SuppressWarnings("deprecation")
public class CameraHook {
    private static final long PREVIEW_RESTART_DELAY_MS = 100;

    private final UmbraCamModule module;
    private final Handler mainHandler;
    private volatile Surface viewfinderSurface;
    private volatile SurfaceTexture viewfinderTexture;
    private final Set<Camera> callbackLogOnce =
            Collections.newSetFromMap(new WeakHashMap<Camera, Boolean>());

    public CameraHook(UmbraCamModule module) {
        this.module = module;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera API (Legacy)");
            hookCameraParameters(param);
            hookViewfinder(param);
            hookPreviewCallbacks(param);
            hookTakePicture(param);
            hookLegacyMethod1(param);
        } catch (Throwable t) {
            module.logHook("[!] FAILED to initialize Camera API hooks: " + t.getMessage());
        }
    }

    private void hookLegacyMethod1(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi")
            Class<?> legacyDeviceClass =
                    param.getClassLoader()
                            .loadClass("android.hardware.camera2.legacy.LegacyCameraDevice");
            for (Method method : legacyDeviceClass.getDeclaredMethods()) {
                if (method.getName().equals("produceFrame")
                        && method.getParameterTypes().length == 5) {
                    module.hook(method)
                            .intercept(
                                    chain -> {
                                        int format = (int) chain.getArgs().get(4);
                                        if (format == 0x21) {
                                            int width = (int) chain.getArgs().get(2);
                                            int height = (int) chain.getArgs().get(3);
                                            byte[] replacement =
                                                    module.handleCapture(width, height);
                                            if (replacement != null) {
                                                Object[] newArgs = chain.getArgs().toArray();
                                                newArgs[1] = replacement;
                                                return chain.proceed(newArgs);
                                            }
                                        }
                                        return chain.proceed(chain.getArgs().toArray());
                                    });
                    module.logHook("[+] Hooked: LegacyCameraDevice#produceFrame");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void hookViewfinder(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method setPreviewDisplay =
                    Camera.class.getDeclaredMethod("setPreviewDisplay", SurfaceHolder.class);
            module.hook(setPreviewDisplay)
                    .intercept(
                            chain -> {
                                SurfaceHolder holder = (SurfaceHolder) chain.getArgs().get(0);
                                if (holder != null && holder.getSurface() != null) {
                                    viewfinderTexture = null;
                                    viewfinderSurface = holder.getSurface();
                                    if (module.getMediaPath() != null
                                            && viewfinderSurface.isValid()) {
                                        module.registerPreviewSurface(viewfinderSurface);
                                        Object[] divertedArgs =
                                                new Object[] {createSurfaceHolderProxy(holder)};
                                        return chain.proceed(divertedArgs);
                                    }
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: Camera#setPreviewDisplay");

            Method setPreviewTexture =
                    Camera.class.getDeclaredMethod("setPreviewTexture", SurfaceTexture.class);
            module.hook(setPreviewTexture)
                    .intercept(
                            chain -> {
                                SurfaceTexture texture = (SurfaceTexture) chain.getArgs().get(0);
                                if (texture != null) {
                                    viewfinderTexture = texture;
                                    viewfinderSurface = null;
                                    if (module.getMediaPath() != null) {
                                        Object[] divertedArgs =
                                                new Object[] {module.getDummySurfaceTexture()};
                                        return chain.proceed(divertedArgs);
                                    }
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: Camera#setPreviewTexture");

            Method startPreview = Camera.class.getDeclaredMethod("startPreview");
            module.hook(startPreview)
                    .intercept(
                            chain -> {
                                Object result = chain.proceed(chain.getArgs().toArray());
                                if (module.getMediaPath() != null) {
                                    SurfaceTexture targetTexture = viewfinderTexture;
                                    Surface targetSurface = viewfinderSurface;
                                    if (targetTexture != null) {
                                        module.recordPipelineNode("Camera1(SurfaceTexture)");
                                        module.handleCamera1Preview(targetTexture);
                                    } else if (targetSurface != null && targetSurface.isValid()) {
                                        module.recordPipelineNode("Camera1(SurfaceHolder)");
                                        module.registerPreviewSurface(targetSurface);
                                        module.handleModernPreview(targetSurface);
                                    }
                                }
                                return result;
                            });
            module.logHook("[+] Hooked: Camera#startPreview");

            Method stopPreview = Camera.class.getDeclaredMethod("stopPreview");
            module.hook(stopPreview)
                    .intercept(
                            chain -> {
                                Object result = chain.proceed(chain.getArgs().toArray());
                                module.stopEngine();
                                return result;
                            });
            module.logHook("[+] Hooked: Camera#stopPreview");

            Method release = Camera.class.getDeclaredMethod("release");
            module.hook(release)
                    .intercept(
                            chain -> {
                                Object result = chain.proceed(chain.getArgs().toArray());
                                viewfinderTexture = null;
                                viewfinderSurface = null;
                                module.stopEngine();
                                return result;
                            });
            module.logHook("[+] Hooked: Camera#release");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook viewfinder: " + t.getMessage());
        }
    }

    /**
     * Wraps a SurfaceHolder so that Camera#setPreviewDisplay binds the physical camera to the dummy
     * surface while leaving the real Surface free for ExoPlayer rendering.
     */
    private SurfaceHolder createSurfaceHolderProxy(SurfaceHolder delegate) {
        if (Proxy.isProxyClass(delegate.getClass())) {
            try {
                if (Proxy.getInvocationHandler(delegate) instanceof SurfaceHolderDiversionHandler) {
                    return delegate;
                }
            } catch (Throwable ignored) {
            }
        }
        ClassLoader loader = SurfaceHolder.class.getClassLoader();
        if (loader == null) loader = CameraHook.class.getClassLoader();
        return (SurfaceHolder)
                Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {SurfaceHolder.class},
                        new SurfaceHolderDiversionHandler(delegate));
    }

    private final class SurfaceHolderDiversionHandler implements InvocationHandler {
        private final SurfaceHolder delegate;

        private SurfaceHolderDiversionHandler(SurfaceHolder delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getSurface".equals(method.getName()) && module.getMediaPath() != null) {
                return module.getDummySurface();
            }
            return method.invoke(delegate, args);
        }
    }

    /**
     * Some Camera1 clients consume NV21 frames through PreviewCallback instead of rendering to a
     * Surface. Wrap all public callback registration variants so those clients receive virtual
     * frames as well.
     */
    private void hookPreviewCallbacks(XposedModuleInterface.PackageReadyParam param) {
        try {
            int hooked = 0;
            for (Method method : Camera.class.getDeclaredMethods()) {
                String name = method.getName();
                if (!(name.equals("setPreviewCallback")
                        || name.equals("setPreviewCallbackWithBuffer")
                        || name.equals("setOneShotPreviewCallback"))) {
                    continue;
                }
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length != 1
                        || !Camera.PreviewCallback.class.isAssignableFrom(parameterTypes[0])) {
                    continue;
                }
                module.hook(method)
                        .intercept(
                                chain -> {
                                    Object callback = chain.getArgs().get(0);
                                    if (callback == null || isUmbraCallbackProxy(callback)) {
                                        return chain.proceed(chain.getArgs().toArray());
                                    }
                                    Object[] args = chain.getArgs().toArray();
                                    args[0] =
                                            createCallbackProxy(callback, parameterTypes[0], name);
                                    module.logHook("[*] Camera1 callback registered: " + name);
                                    return chain.proceed(args);
                                });
                hooked++;
                module.logHook("[+] Hooked: Camera#" + name);
            }
            hookCallbackBuffer(param);
            if (hooked == 0) {
                module.logHook("[!] No Camera1 preview callback methods found");
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook Camera1 preview callbacks: " + t.getMessage());
        }
    }

    private void hookCallbackBuffer(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method addCallbackBuffer =
                    Camera.class.getDeclaredMethod("addCallbackBuffer", byte[].class);
            module.hook(addCallbackBuffer)
                    .intercept(
                            chain -> {
                                if (module.getMediaPath() != null) {
                                    module.recordPipelineNode("Camera1(PreviewCallbackBuffer)");
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: Camera#addCallbackBuffer");
        } catch (Throwable t) {
            module.logHook("[!] Camera#addCallbackBuffer hook unavailable: " + t.getMessage());
        }
    }

    private boolean isUmbraCallbackProxy(Object callback) {
        if (!Proxy.isProxyClass(callback.getClass())) return false;
        try {
            return Proxy.getInvocationHandler(callback) instanceof PreviewCallbackHandler;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object createCallbackProxy(Object delegate, Class<?> callbackType, String hookName) {
        ClassLoader loader = callbackType.getClassLoader();
        if (loader == null) loader = CameraHook.class.getClassLoader();
        return Proxy.newProxyInstance(
                loader,
                new Class<?>[] {callbackType},
                new PreviewCallbackHandler(delegate, hookName));
    }

    private final class PreviewCallbackHandler implements InvocationHandler {
        private final Object delegate;
        private final String hookName;

        private PreviewCallbackHandler(Object delegate, String hookName) {
            this.delegate = delegate;
            this.hookName = hookName;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!"onPreviewFrame".equals(method.getName())
                    || args == null
                    || args.length < 2
                    || !(args[0] instanceof byte[])
                    || !(args[1] instanceof Camera)) {
                return method.invoke(delegate, args);
            }

            byte[] original = (byte[]) args[0];
            Camera camera = (Camera) args[1];
            byte[] callbackFrame = original;
            try {
                Camera.Size size = camera.getParameters().getPreviewSize();
                if (size != null && module.getMediaPath() != null) {
                    byte[] replacement = module.handlePreviewFrame(size.width, size.height);
                    if (replacement != null) {
                        if (original != null && original.length >= replacement.length) {
                            System.arraycopy(replacement, 0, original, 0, replacement.length);
                            callbackFrame = original;
                        } else {
                            callbackFrame = replacement;
                        }
                        synchronized (callbackLogOnce) {
                            if (callbackLogOnce.add(camera)) {
                                module.recordPipelineNode(
                                        "Camera1("
                                                + hookName
                                                + " "
                                                + size.width
                                                + "x"
                                                + size.height
                                                + " NV21)");
                                module.logHook(
                                        "[+] Camera1 preview frame replacement active: "
                                                + size.width
                                                + "x"
                                                + size.height
                                                + " ("
                                                + hookName
                                                + ")");
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                module.logHook("[!] Camera1 preview frame replacement failed: " + t.getMessage());
            }

            ((Camera.PreviewCallback) delegate).onPreviewFrame(callbackFrame, camera);
            return null;
        }
    }

    private void hookCameraParameters(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> paramsClass = Camera.Parameters.class;
            Method setPreviewSize =
                    paramsClass.getDeclaredMethod("setPreviewSize", int.class, int.class);
            module.hook(setPreviewSize)
                    .intercept(
                            chain -> {
                                int pw = (int) chain.getArgs().get(0);
                                int ph = (int) chain.getArgs().get(1);
                                module.recordPipelineNode(
                                        "Camera1(Preview " + pw + "x" + ph + " NV21)");
                                module.logHook(
                                        "[*] Legacy Activity: setPreviewSize " + pw + "x" + ph);
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: Camera.Parameters#setPreviewSize");
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook Camera.Parameters: " + t.getMessage());
        }
    }

    private void hookTakePicture(XposedModuleInterface.PackageReadyParam param) {
        try {
            for (Method m : Camera.class.getDeclaredMethods()) {
                if (m.getName().equals("takePicture") && m.getParameterTypes().length == 4) {
                    module.hook(m)
                            .intercept(
                                    chain -> {
                                        if (module.getMediaPath() == null)
                                            return chain.proceed(chain.getArgs().toArray());
                                        Camera camera = (Camera) chain.getThisObject();
                                        PictureCallback jpegCallback =
                                                (PictureCallback) chain.getArgs().get(3);
                                        if (jpegCallback == null)
                                            return chain.proceed(chain.getArgs().toArray());

                                        module.triggerCaptureState();
                                        Camera.Parameters params = camera.getParameters();
                                        int picW = params.getPictureSize().width;
                                        int picH = params.getPictureSize().height;
                                        byte[] imageData = module.handleCapture(picW, picH);

                                        if (imageData == null)
                                            return chain.proceed(chain.getArgs().toArray());

                                        module.recordPipelineNode(
                                                "Camera1(takePicture "
                                                        + picW
                                                        + "x"
                                                        + picH
                                                        + " JPEG)");
                                        module.reportPipelineCapture(picW, picH, imageData);

                                        final ShutterCallback shutterCallback =
                                                (ShutterCallback) chain.getArgs().get(0);
                                        final PictureCallback rawCallback =
                                                (PictureCallback) chain.getArgs().get(1);
                                        final PictureCallback postviewCallback =
                                                (PictureCallback) chain.getArgs().get(2);

                                        Runnable deliver =
                                                () -> {
                                                    try {
                                                        if (shutterCallback != null)
                                                            shutterCallback.onShutter();
                                                        if (rawCallback != null)
                                                            rawCallback.onPictureTaken(
                                                                    null, camera);
                                                        if (postviewCallback != null)
                                                            postviewCallback.onPictureTaken(
                                                                    null, camera);
                                                        jpegCallback.onPictureTaken(
                                                                imageData, camera);
                                                        mainHandler.postDelayed(
                                                                () -> {
                                                                    try {
                                                                        camera.startPreview();
                                                                    } catch (Throwable ignored) {
                                                                    }
                                                                },
                                                                PREVIEW_RESTART_DELAY_MS);
                                                    } catch (Throwable ignored) {
                                                    }
                                                };
                                        if (Looper.myLooper() == Looper.getMainLooper())
                                            deliver.run();
                                        else mainHandler.post(deliver);
                                        return null;
                                    });
                    module.logHook("[+] Hooked: Camera#takePicture (Legacy)");
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Failed to hook takePicture: " + t.getMessage());
        }
    }
}