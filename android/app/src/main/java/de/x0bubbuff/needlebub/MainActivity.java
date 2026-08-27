package de.x0bubbuff.needlebub;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NeedleBubPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStop() {
        super.onStop();
        ((NeedleBubApplication) getApplication()).getDeveloperDataSettings().setLabAuthenticated(false);
    }
}
