package com.fongmi.android.tv;
import androidx.fragment.app.FragmentActivity;
import com.fongmi.android.tv.source.UpdateJob;
import com.fongmi.android.tv.source.UpdateRepository;
import com.fongmi.android.tv.ui.activity.UpdateActivity;
import com.fongmi.android.tv.utils.Notify;
/** Home startup never interrupts playback or starts an installer. */
public class Updater {
    private boolean manual;
    public static Updater create() { return new Updater(); }
    public Updater force() { manual = true; return this; }
    public void start(FragmentActivity activity) {
        UpdateJob.schedule(activity);
        if (manual) UpdateActivity.start(activity);
        else if (UpdateRepository.hasReadyFile()) Notify.show("新版已下载，可在设置 → 版本中安装");
    }
}
