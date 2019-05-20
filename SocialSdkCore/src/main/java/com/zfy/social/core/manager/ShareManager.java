package com.zfy.social.core.manager;

import android.app.Activity;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.zfy.social.core.SocialSdk;
import com.zfy.social.core.common.Target;
import com.zfy.social.core.exception.SocialError;
import com.zfy.social.core.listener.OnShareListener;
import com.zfy.social.core.listener.OnShareStateListener;
import com.zfy.social.core.listener.ShareInterceptor;
import com.zfy.social.core.model.ShareObj;
import com.zfy.social.core.model.ShareResult;
import com.zfy.social.core.platform.IPlatform;
import com.zfy.social.core.util.FileUtil;
import com.zfy.social.core.util.ShareObjCheckUtil;
import com.zfy.social.core.util.SocialUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

import bolts.CancellationTokenSource;
import bolts.Task;

import static com.zfy.social.core.manager.GlobalPlatform.KEY_ACTION_TYPE;

/**
 * CreateAt : 2017/5/19
 * Describe : 分享管理类，使用该类进行分享操作
 *
 * @author chendong
 */
public class ShareManager {

    public static final String TAG = ShareManager.class.getSimpleName();

    private static _ShareMgr sShareMgr;


    // 分享
    public static void share(
            final Activity activity,
            @Target.ShareTarget final int shareTarget,
            final ShareObj shareObj,
            final OnShareStateListener listener
    ) {
        if (sShareMgr != null) {
            sShareMgr.onHostActivityDestroy();
        }
        sShareMgr = new _ShareMgr();
        sShareMgr.preShare(activity, shareTarget, shareObj, listener);
    }

    // 开始分享
    static void actionShare(Activity activity) {
        if (sShareMgr != null) {
            sShareMgr.postShare(activity);
        }
    }

    // 发起分享的页面被销毁
    static void onUIDestroy() {
        if (sShareMgr != null) {
            sShareMgr.onUIDestroy();
        }
    }


    private static class _ShareMgr implements LifecycleObserver {

        private OnShareStateListener stateListener;
        private OnShareListenerWrap shareListener;

        private int currentTarget;
        private ShareObj currentObj;
        private CancellationTokenSource cts;

        private WeakReference<Activity> fakeActivity;

        @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        public void onHostActivityDestroy() {
            onProcessFinished();
            SocialUtil.e("chendong", "页面销毁，回收资源");
        }

        // 流程结束，回收资源
        private void onProcessFinished() {
            if (cts != null) {
                cts.cancel();
            }
            if (shareListener != null) {
                shareListener.clear();
            }
            if (fakeActivity != null) {
                GlobalPlatform.release(fakeActivity.get());
                fakeActivity.clear();
            }
            currentTarget = -1;
            cts = null;
            currentObj = null;
            stateListener = null;
            shareListener = null;
            fakeActivity = null;
            sShareMgr = null;
            SocialUtil.e("chendong", "分享过程结束，回收资源");
        }


        /**
         * 开始分享，供外面调用
         *
         * @param activity    发起分享的 activity
         * @param shareTarget 分享目标
         * @param shareObj    分享对象
         * @param listener    分享监听
         */
        private void preShare(
                final Activity activity,
                @Target.ShareTarget final int shareTarget,
                final ShareObj shareObj,
                final OnShareStateListener listener
        ) {
            if (cts != null) {
                cts.cancel();
            }
            cts = new CancellationTokenSource();
            if (activity instanceof LifecycleOwner) {
                ((LifecycleOwner) activity).getLifecycle().addObserver(this);
            }
            currentObj = shareObj;
            currentTarget = -1;
            listener.onState(ShareResult.startOf(shareTarget, currentObj));
            Task.callInBackground(() -> {
                currentObj = execInterceptors(activity, currentObj);
                return currentObj;
            }, cts.getToken()).continueWith(task -> {
                if (task.isFaulted()) {
                    throw task.getError();
                }
                if (task.getResult() == null) {
                    throw SocialError.make(SocialError.CODE_COMMON_ERROR, "ShareManager#preShare Result is Null");
                }
                preDoShare(activity, shareTarget, task.getResult(), listener);
                return true;
            }, Task.UI_THREAD_EXECUTOR).continueWith(task -> {
                if (task.isFaulted()) {
                    SocialError error;
                    Exception exception = task.getError();
                    if (exception instanceof SocialError) {
                        error = (SocialError) exception;
                    } else {
                        error = SocialError.make(SocialError.CODE_COMMON_ERROR, "ShareManager#preShare() error", exception);
                    }
                    listener.onState(ShareResult.failOf(shareTarget, currentObj, error));
                }
                return true;
            });
        }

        /**
         * 准备完成数据，开始分享
         *
         * @param activity        发起分享的 activity
         * @param shareTarget     目标
         * @param shareObj        分享对象
         * @param onShareListener 分享回调
         */
        private void preDoShare(
                Activity activity,
                @Target.ShareTarget int shareTarget,
                ShareObj shareObj,
                OnShareStateListener onShareListener
        ) {
            stateListener = onShareListener;
            try {
                ShareObjCheckUtil.checkShareObjParams(activity, shareTarget, shareObj);
            } catch (Exception e) {
                e.printStackTrace();
                SocialError error = (e instanceof SocialError)
                        ? (SocialError) e
                        : SocialError.make(SocialError.CODE_COMMON_ERROR, "ShareManager#preDoShare check obj", e);
                stateListener.onState(ShareResult.failOf(shareTarget, shareObj, error));
                return;
            }

            IPlatform platform = GlobalPlatform.newPlatformByTarget(activity, shareTarget);
            if (!platform.isInstall(activity)) {
                stateListener.onState(ShareResult.failOf(shareTarget, shareObj, SocialError.make(SocialError.CODE_NOT_INSTALL)));
                return;
            }
            if (platform.getUIKitClazz() == null) {
                shareListener = new OnShareListenerWrap(stateListener);
                platform.initOnShareListener(shareListener);
                platform.share(activity, shareTarget, shareObj);
            } else {
                currentTarget = shareTarget;
                Intent intent = new Intent(activity, platform.getUIKitClazz());
                intent.putExtra(GlobalPlatform.KEY_ACTION_TYPE, GlobalPlatform.ACTION_TYPE_SHARE);
                intent.putExtra(GlobalPlatform.KEY_SHARE_MEDIA_OBJ, shareObj);
                intent.putExtra(GlobalPlatform.KEY_SHARE_TARGET, shareTarget);
                activity.startActivity(intent);
                activity.overridePendingTransition(0, 0);
            }
        }

        /**
         * 激活分享，由透明 Activity 真正的激活分享
         *
         * @param activity 透明 activity
         */
        private void postShare(Activity activity) {
            fakeActivity = new WeakReference<>(activity);
            Intent intent = activity.getIntent();
            int actionType = intent.getIntExtra(KEY_ACTION_TYPE, GlobalPlatform.INVALID_PARAM);
            int shareTarget = intent.getIntExtra(GlobalPlatform.KEY_SHARE_TARGET, GlobalPlatform.INVALID_PARAM);
            ShareObj shareObj = intent.getParcelableExtra(GlobalPlatform.KEY_SHARE_MEDIA_OBJ);
            if (actionType != GlobalPlatform.ACTION_TYPE_SHARE) {
                SocialUtil.e(TAG, "actionType 错误");
                return;
            }
            if (shareTarget == GlobalPlatform.INVALID_PARAM) {
                SocialUtil.e(TAG, "shareTarget Type 无效");
                return;
            }
            if (shareObj == null) {
                SocialUtil.e(TAG, "shareObj == null");
                return;
            }
            if (stateListener == null) {
                SocialUtil.e(TAG, "请设置 OnShareListener");
                return;
            }
            if (GlobalPlatform.getCurrentPlatform() == null) {
                return;
            }
            shareListener = new OnShareListenerWrap(stateListener);
            GlobalPlatform.getCurrentPlatform().initOnShareListener(shareListener);
            GlobalPlatform.getCurrentPlatform().share(activity, shareTarget, shareObj);
        }

        private void onUIDestroy() {
            if (currentTarget != -1 && stateListener != null) {
                if (SocialSdk.opts().isShareSuccessIfStay()) {
                    stateListener.onState(ShareResult.successOf(currentTarget, currentObj));
                } else {
                    stateListener.onState(ShareResult.failOf(currentTarget, currentObj, SocialError.make(SocialError.CODE_STAY_OTHER_APP)));
                }
            }
        }

        private ShareObj execInterceptors(Context context, ShareObj obj) {
            ShareObj result = obj;
            List<ShareInterceptor> interceptors = SocialSdk.getShareInterceptors();
            if (interceptors != null && interceptors.size() > 0) {
                for (ShareInterceptor interceptor : interceptors) {
                    ShareObj temp = interceptor.intercept(context, result);
                    if (temp != null) {
                        result = temp;
                    }
                }
            }
            return result;
        }
    }

    public static class ImgInterceptor implements ShareInterceptor {
        @Override
        public ShareObj intercept(Context context, ShareObj obj) {
            String thumbImagePath = obj.getThumbImagePath();
            // 图片路径为网络路径，下载为本地图片
            if (!TextUtils.isEmpty(thumbImagePath) && FileUtil.isHttpPath(thumbImagePath)) {
                File file = SocialSdk.getRequestAdapter().getFile(thumbImagePath);
                if (FileUtil.isExist(file)) {
                    obj.setThumbImagePath(file.getAbsolutePath());
                } else if (SocialSdk.opts().getFailImgRes() > 0) {
                    String localPath = FileUtil.mapResId2LocalPath(context, SocialSdk.opts().getFailImgRes());
                    if (FileUtil.isExist(localPath)) {
                        obj.setThumbImagePath(localPath);
                    }
                }
            }
            return obj;
        }
    }


    // 用于分享结束后，回收资源
    private static class OnShareListenerWrap implements OnShareListener {

        private OnShareStateListener listener;

        OnShareListenerWrap(OnShareStateListener listener) {
            this.listener = listener;
        }

        @Override
        public void onStart(int shareTarget, ShareObj obj) {
            if (listener != null) {
                listener.onState(ShareResult.startOf(shareTarget, obj));
            }
        }

        @Override
        public void onSuccess(int target) {
            if (listener != null) {
                listener.onState(ShareResult.successOf(target, sShareMgr.currentObj));
            }
            clear();
            sShareMgr.onProcessFinished();
        }

        @Override
        public void onCancel() {
            if (listener != null) {
                listener.onState(ShareResult.cancelOf(sShareMgr.currentTarget, sShareMgr.currentObj));
            }
            clear();
            sShareMgr.onProcessFinished();
        }


        @Override
        public void onFailure(SocialError e) {
            if (listener != null) {
                listener.onState(ShareResult.failOf(sShareMgr.currentTarget, sShareMgr.currentObj, e));
            }
            clear();
            sShareMgr.onProcessFinished();
        }

        private void clear() {
            listener = null;
        }
    }


}
