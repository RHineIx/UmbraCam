package umbra.cam.hooks;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import io.github.libxposed.api.XposedModuleInterface;

import umbra.cam.xposed.UmbraCamModule;

import java.lang.reflect.Method;

/**
 * WebRTC uses the platform Camera1/Camera2 APIs underneath, so camera-frame replacement remains in
 * the normal camera hooks. This hook exposes the SurfaceTexture owned by WebRTC early, allowing the
 * generic surface pipeline to recognise it as a camera sink without a package-specific profile.
 */
public final class WebRtcHook {
    private final UmbraCamModule module;
    private Surface helperSurface;

    public WebRtcHook(UmbraCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing WebRTC Support");
            Class<?> helper = param.getClassLoader().loadClass("org.webrtc.SurfaceTextureHelper");
            hookStartListening(helper);
            hookStopListening(helper);
            module.logHook("[+] WebRTC support hooks installed");
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) {
            module.logHook("[!] WebRTC hook setup failed: " + t.getMessage());
        }
    }

    private void hookStartListening(Class<?> helper) {
        for (Method startListening : helper.getDeclaredMethods()) {
            if (!startListening.getName().equals("startListening")
                    || startListening.getParameterTypes().length != 1) continue;
            module.hook(startListening)
                    .intercept(
                            chain -> {
                                Object result = chain.proceed(chain.getArgs().toArray());
                                registerHelperSurface(chain.getThisObject());
                                return result;
                            });
            module.logHook("[+] Hooked: SurfaceTextureHelper#startListening");
        }
    }

    private void hookStopListening(Class<?> helper) throws NoSuchMethodException {
        Method stopListening = helper.getDeclaredMethod("stopListening");
        module.hook(stopListening)
                .intercept(
                        chain -> {
                            module.logHook("[*] Activity: WebRTC SurfaceTextureHelper stopped");
                            if (helperSurface != null) {
                                try {
                                    helperSurface.release();
                                } catch (Throwable ignored) {
                                }
                                helperSurface = null;
                            }
                            return chain.proceed(chain.getArgs().toArray());
                        });
        module.logHook("[+] Hooked: SurfaceTextureHelper#stopListening");
    }

    private void registerHelperSurface(Object helper) {
        try {
            Method getter = helper.getClass().getMethod("getSurfaceTexture");
            Object value = getter.invoke(helper);
            if (!(value instanceof SurfaceTexture)) return;
            if (helperSurface != null) {
                try {
                    helperSurface.release();
                } catch (Throwable ignored) {
                }
            }
            helperSurface = new Surface((SurfaceTexture) value);
            module.recordPipelineNode("WebRTC(TextureHelper)");
            module.registerPreviewSurface(helperSurface);
            module.logHook("[+] Activity: WebRTC Camera frame surface registered");
        } catch (Throwable t) {
            module.logHook("[!] WebRTC: Could not register frame surface: " + t.getMessage());
        }
    }
}
