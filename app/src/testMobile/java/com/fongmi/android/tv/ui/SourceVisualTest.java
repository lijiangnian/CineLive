package com.fongmi.android.tv.ui;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import com.fongmi.android.tv.App;
import android.graphics.*;
import android.view.View;
import java.io.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=App.class,shadows=SourceEntryTest.NativePluginLoader.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SourceVisualTest {
 @Test public void renderSourcePages() throws Exception {
   capture(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class,"catalog-phone",360,800);
   capture(com.fongmi.android.tv.ui.activity.SourceStatusActivity.class,"maintenance-phone",360,800);
   capture(com.fongmi.android.tv.ui.activity.SourceCatalogActivity.class,"catalog-landscape",960,540);
 }
 private void capture(Class<? extends android.app.Activity> cls,String name,int w,int h)throws Exception {
   try(var controller=Robolectric.buildActivity(cls).setup()) {
     View root=controller.get().findViewById(android.R.id.content);
     root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);
     Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(new Canvas(b));
     File target=new File("build/reports/source-ui/"+name+".png");target.getParentFile().mkdirs();
     try(FileOutputStream out=new FileOutputStream(target)){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
   }
 }
}
