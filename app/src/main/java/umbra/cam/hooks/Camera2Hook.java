package umbra.cam.hooks;

import android.annotation.SuppressLint;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.view.Surface;

import io.github.libxposed.api.XposedModuleInterface;

import umbra.cam.xposed.UmbraCamModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Hooks for the Camera2 API (android.hardware.camera2). Consolidates Camera2 session discovery,
 * per-surface OutputConfiguration preview hijacking, and CaptureRequest target diversion.
 */
public class Camera2Hook {
    private final UmbraCamModule module;
    private volatile Object activeCameraDevice;

    public Camera2Hook(UmbraCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Camera2 API");
            hookDiscovery(param);
            hookModernHijack(param);
            hookSurgicalDiverter(param);
            hookCleanup(param);
            module.logHook("[+] Camera2 API hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Failed to initialize Camera2 API hooks: " + t.getMessage());
        }
    }

    private void hookDiscovery(XposedModuleInterface.PackageReadyParam param) {
        try {
            @SuppressLint("PrivateApi")
            Class<?> cameraDeviceClass =
                    param.getClassLoader()
                            .loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            for (Method method : cameraDeviceClass.getDeclaredMethods()) {
                if (method.getName().startsWith("createCaptureSession")) {
                    final String methodName = method.getName();
                    module.hook(method)
                            .intercept(
                                    chain -> {
                                        Object thisDevice = chain.getThisObject();
                                        if (thisDevice != null) {
                                            activeCameraDevice = thisDevice;
                                        }
                                        module.logHook(
                                                "[*] Activity: CameraDeviceImpl#" + methodName);
                                        if (!methodName.equals("createCaptureSessionInternal")) {
                                            module.incrementSessionGeneration();
                                        }
                                        for (Object arg : chain.getArgs()) {
                                            inspectDiscoveryArgument(arg);
                                        }
                                        try {
                                            return chain.proceed(chain.getArgs().toArray());
                                        } finally {
                                            if (methodName.equals("createCaptureSessionInternal")) {
                                                module.onSessionConfigured();
                                            }
                                        }
                                    });
                    module.logHook("[+] Hooked: CameraDeviceImpl#" + methodName);
                }
            }
        } catch (Throwable t) {
            module.logHook("[!] Discovery hook failed: " + t.getMessage());
        }
    }

    private void inspectDiscoveryArgument(Object arg) {
        if (arg instanceof Surface) {
            Surface s = (Surface) arg;
            module.logSessionOutput(s);
            if (!module.isDummySurface(s) && module.isPreviewSurface(s)) {
                module.registerPreviewSurface(s);
            }
        } else if (arg instanceof OutputConfiguration) {
            Surface s = ((OutputConfiguration) arg).getSurface();
            if (s != null) inspectDiscoveryArgument(s);
        } else if (arg instanceof Collection) {
            for (Object item : (Collection<?>) arg) inspectDiscoveryArgument(item);
        }
    }

    private void hookModernHijack(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> ocClass =
                    param.getClassLoader()
                            .loadClass("android.hardware.camera2.params.OutputConfiguration");
            for (Constructor<?> constructor : ocClass.getDeclaredConstructors()) {
                module.hook(constructor)
                        .intercept(
                                chain -> {
                                    if (module.getMediaPath() == null)
                                        return chain.proceed(chain.getArgs().toArray());

                                    Surface surface = null;
                                    int surfaceIndex = -1;
                                    List<Object> args = chain.getArgs();
                                    for (int i = 0; i < args.size(); i++) {
                                        if (args.get(i) instanceof Surface
                                                && ((Surface) args.get(i)).isValid()) {
                                            surface = (Surface) args.get(i);
                                            surfaceIndex = i;
                                            break;
                                        }
                                    }

                                    if (surface != null && !module.isDummySurface(surface)) {
                                        boolean isPreview = module.isPreviewSurface(surface);
                                        boolean alreadyChosen = module.isHijackedPreview(surface);

                                        if (alreadyChosen
                                                || (isPreview && !module.getPreviewSwapped())) {
                                            String sStr = surface.toString();
                                            String surfaceType =
                                                    sStr.contains("SurfaceView")
                                                            ? "SurfaceView"
                                                            : "SurfaceTexture";
                                            module.recordPipelineNode(
                                                    "Camera2(" + surfaceType + ")");
                                            module.logHook(
                                                    "[!] Action: Hijacking Preview Surface via"
                                                            + " OutputConfiguration ("
                                                            + sStr
                                                            + ")");
                                            module.setPreviewSwapped(true);
                                            module.markPreviewHijacked(surface);
                                            module.handleModernPreview(surface);

                                            Object[] newArgs = chain.getArgs().toArray();
                                            newArgs[surfaceIndex] =
                                                    module.getDummySurfaceFor(surface);
                                            return chain.proceed(newArgs);
                                        }
                                    }
                                    return chain.proceed(chain.getArgs().toArray());
                                });
            }
            module.logHook("[+] Hooked: OutputConfiguration Constructor");
        } catch (Throwable t) {
            module.logHook("[!] Modern Hijack hook failed: " + t.getMessage());
        }
    }

    private void hookSurgicalDiverter(XposedModuleInterface.PackageReadyParam param) {
        try {
            Method addTarget =
                    CaptureRequest.Builder.class.getDeclaredMethod("addTarget", Surface.class);
            module.hook(addTarget)
                    .intercept(
                            chain -> {
                                Surface surface = (Surface) chain.getArgs().get(0);
                                if (surface != null
                                        && !module.isDummySurface(surface)
                                        && module.getMediaPath() != null) {
                                    CaptureRequest.Builder builder =
                                            (CaptureRequest.Builder) chain.getThisObject();
                                    Integer intent = null;
                                    try {
                                        intent = builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT);
                                    } catch (Throwable ignored) {
                                    }

                                    if (intent != null
                                            && intent
                                                    == CaptureRequest
                                                            .CONTROL_CAPTURE_INTENT_STILL_CAPTURE) {
                                        module.logHook("[*] Activity: addTarget [STILL_CAPTURE]");
                                        module.recordPipelineNode("Camera2(StillCapture)");
                                        module.triggerCaptureState();
                                        if (module.isHijackedPreview(surface)) {
                                            return chain.proceed(
                                                    new Object[] {
                                                        module.getDummySurfaceFor(surface)
                                                    });
                                        }
                                        return chain.proceed(chain.getArgs().toArray());
                                    }

                                    if (intent != null
                                            && intent
                                                    == CaptureRequest
                                                            .CONTROL_CAPTURE_INTENT_VIDEO_RECORD) {
                                        module.logHook("[*] Activity: addTarget [VIDEO_RECORD]");
                                        module.recordPipelineNode("Camera2(VideoRecord)");
                                        if (module.isHijackedPreview(surface)) {
                                            return chain.proceed(
                                                    new Object[] {
                                                        module.getDummySurfaceFor(surface)
                                                    });
                                        }
                                        return chain.proceed(chain.getArgs().toArray());
                                    }

                                    if (intent != null
                                            && intent
                                                    == CaptureRequest
                                                            .CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT) {
                                        module.logHook("[*] Activity: addTarget [VIDEO_SNAPSHOT]");
                                        module.recordPipelineNode("Camera2(VideoSnapshot)");
                                        if (module.isHijackedPreview(surface)) {
                                            return chain.proceed(
                                                    new Object[] {
                                                        module.getDummySurfaceFor(surface)
                                                    });
                                        }
                                        return chain.proceed(chain.getArgs().toArray());
                                    }

                                    if (module.isHijackedPreview(surface)
                                            || module.isPreviewSurface(surface)) {
                                        module.markPreviewHijacked(surface);
                                        Object[] newArgs =
                                                new Object[] {module.getDummySurfaceFor(surface)};
                                        return chain.proceed(newArgs);
                                    }
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: CaptureRequest.Builder#addTarget");
        } catch (Throwable t) {
            module.logHook("[!] Surgical Diverter hook failed: " + t.getMessage());
        }
    }

    private void hookCleanup(XposedModuleInterface.PackageReadyParam param) {
        try {
            Class<?> cameraDeviceClass =
                    param.getClassLoader()
                            .loadClass("android.hardware.camera2.impl.CameraDeviceImpl");
            Method deviceClose = cameraDeviceClass.getDeclaredMethod("close");
            module.hook(deviceClose)
                    .intercept(
                            chain -> {
                                String threadName = Thread.currentThread().getName();
                                if (threadName != null && threadName.contains("Finalizer")) {
                                    return chain.proceed(chain.getArgs().toArray());
                                }
                                Object closingDevice = chain.getThisObject();
                                module.logHook("[*] Activity: CameraDeviceImpl#close");
                                if (closingDevice == null || closingDevice == activeCameraDevice) {
                                    activeCameraDevice = null;
                                    module.stopEngine();
                                } else {
                                    module.onSessionConfigured();
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });

            Class<?> sessionClass =
                    param.getClassLoader()
                            .loadClass("android.hardware.camera2.impl.CameraCaptureSessionImpl");
            Method sessionClose = sessionClass.getDeclaredMethod("close");
            module.hook(sessionClose)
                    .intercept(
                            chain -> {
                                String threadName = Thread.currentThread().getName();
                                if (threadName == null || !threadName.contains("Finalizer")) {
                                    module.logHook("[*] Activity: CameraCaptureSessionImpl#close");
                                    module.onCaptureSessionClosed();
                                }
                                return chain.proceed(chain.getArgs().toArray());
                            });
            module.logHook("[+] Hooked: Camera2 Cleanup methods");
        } catch (Throwable t) {
            module.logHook("[!] Cleanup hook failed: " + t.getMessage());
        }
    }
}
