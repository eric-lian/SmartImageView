package com.example.googleplay.image;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.example.googleplay.R;
import com.example.googleplay.application.utils.DrawableUtils;
import com.example.googleplay.application.utils.FileUtils;
import com.example.googleplay.application.utils.IOUtils;
import com.example.googleplay.application.utils.LogUtils;
import com.example.googleplay.application.utils.StringUtils;
import com.example.googleplay.application.utils.SystemUtils;
import com.example.googleplay.application.utils.UIUtils;
import com.example.googleplay.http.HttpHelper;
import com.example.googleplay.http.HttpHelper.HttpResult;
import com.example.googleplay.manager.ThreadManager;
import com.example.googleplay.manager.ThreadManager.ThreadPoolProxy;

/**
 * Created by mwqi on 2014/6/8.
 */
public class ImageLoader {
	/** 图片下载的线程池名称 */
	public static final String THREAD_POOL_NAME = "IMAGE_THREAD_POOL";
	/** 图片缓存最大数量 */
	public static final int MAX_DRAWABLE_COUNT = 100;
	/** 图片的KEY缓存 */
	private static ConcurrentLinkedQueue<String> mKeyCache =  new ConcurrentLinkedQueue<String>();
	/** 图片的缓存 */
	private static Map<String, Drawable> mDrawableCache = new ConcurrentHashMap<String, Drawable>();
	
	private static BitmapFactory.Options mOptions = new BitmapFactory.Options();
	/** 图片下载的线程池 */
	private static ThreadPoolProxy mThreadPool = ThreadManager.getSinglePool(THREAD_POOL_NAME);
	/** 用于记录图片下载的任务，以便取消 */
	private static ConcurrentHashMap<String, Runnable> mMapRuunable = new ConcurrentHashMap<String, Runnable>();
	/** 图片的总大小 */
	private static long mTotalSize;

	static {
		mOptions.inDither = false;// 设置为false，将不考虑图片的抖动值，这会减少图片的内存占用
		mOptions.inPurgeable = true;// 设置为ture，表示允许系统在内存不足时，删除bitmap的数组。
		mOptions.inInputShareable = true;// 和inPurgeable配合使用，如果inPurgeable是false，那么该参数将被忽略，表示是否对bitmap的数组进行共享
	}

	/** 加载图片 */
	public static void load(ImageView view, String url) {
		if (view == null || StringUtils.isEmpty(url)) {
			return;
		}
		
		view.setTag(url);//把控件和图片的url进行绑定，因为加载是一个耗时的，等加载完毕了需要判定该控件是否和该url匹配
		
		Drawable drawable = loadFromMemory(url);//从内存中加载
		
		if (drawable != null) {
			setImageSafe(view, url, drawable);//如果内存中加载到了，直接设置图片
		} else {
			view.setImageResource(R.drawable.ic_default);//如果没加载到，设置默认图片，并异步加载
			asyncLoad(view, url);
		}
	}

	/** 异步加载 */
	private static void asyncLoad(final ImageView view, final String url) {
		//先创建一个runnable封装执行过程
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				//先从线程队列中删除当前正在排队的线程任务，一点进入线程就要删除掉了
				mMapRuunable.remove(url);
				//从本地加载图片
				Drawable drawable = loadFromLocal(url);
				//如果本地缓存的图片为空，则从网络上面加载
				if (drawable == null) {
					drawable = loadFromNet(url);
				}
				//设置控件的图片
				if (drawable != null) {
					setImageSafe(view, url, drawable);
				}
			}
		};
		cancel(url);//先取消这个url的下载，无论有没有就先取消一次
		mMapRuunable.put(url, runnable);//记住这个runnable，以便后面取消
		mThreadPool.execute(runnable);//执行任务
	}

	/** 取消下载 */
	public static void cancel(String url) {
		//根据url来获取指定的runnable
		Runnable runnable = mMapRuunable.remove(url);
		if (runnable != null) {
			//从线程池中删除该任务，如果任务已经开始下载了，就无法删除
			mThreadPool.cancel(runnable);
		}
	}

	/** 从内存中加载 */
	private static Drawable loadFromMemory(String url) {
		//url是key，根据url取出从内存中缓存的图片
		Drawable drawable = mDrawableCache.get(url);
		if (drawable != null) {
			//从内存中获取到了，需要重新放到内存队列的最后，以便满足LRC
			//一般缓存算法有两种，第一是LFU，按使用次数来判定删除优先级，使用次数最少的最先删除
			//还有一个就是LRC，就是按最后使用时间来判定删除优先级，最后使用时间越早的最先删除
			addDrawableToMemory(url, drawable);
		}
		return drawable;
	}

	/** 从本地设备中加载 */
	private static Drawable loadFromLocal(String url) {
		Bitmap bitmap = null;
		Drawable drawable = null;
		//获的缓存图片的目录，如果手机内存卡中可以使用就是用手机内存卡，如果手机内存可以使用，就使用手机data
		String path = FileUtils.getIconDir();
		FileInputStream fis = null;
		try {
			//获取流
			fis = new FileInputStream(new File(path + url));
			if (fis != null) {
				/*本地就是jni
				 尽量不要使用setImageBitmap或setImageResource或BitmapFactory.decodeResource来设置一张大图，
				 因为这些函数在完成decode后，最终都是通过java层的createBitmap来完成的，需要消耗更多内存。
				 因此，改用先通过BitmapFactory.decodeStream方法，创建出一个bitmap，再将其设为ImageView的 source，
				decodeStream最大的秘密在于其直接调用JNI>>nativeDecodeAsset()来完成decode，
				无需再使用java层的createBitmap，从而节省了java层的空间。  
				如果在读取时加上图片的Config参数，可以跟有效减少加载的内存，从而跟有效阻止抛out of Memory异常
				另外，decodeStream直接拿的图片来读取字节码了， 不会根据机器的各种分辨率来自动适应， 
				使用了decodeStream之后，需要在hdpi和mdpi，ldpi中配置相应的图片资源， 
				否则在不同分辨率机器上都是同样大小（像素点数量），显示出来的大小就不对了。
				 */
				// BitmapFactory.decodeByteArray(data, offset, length)
				// BitmapFactory.decodeFile(pathName)
				// BitmapFactory.decodeStream(is)
				// 上面三个分析源码可知，他们都是在Java层创建byte数组，然后把数据传递给本地代码。
				// 下面这个是把文件描述符传递给本地代码，由本地代码去创建图片,jni底层
				// 优点，由于是本地代码创建的，那么byte数组的内存占用不会算到应用内存中，并且一旦内存不足，将会把bitmap的数组回收掉，而bitmap不会被回收
				// 当显示的时候，发现bitmap的数组为空时，将会再次根据文件描述符去加载图片，此时可能由于加载耗时造成界面卡顿，但总比OOM要好得多。
				// 由于本地代码在创建图片时，没有对图片进行校验，所以如果文件不完整，或者根本就不是一个图片时，系统也不会报错，仍然会返回一个bitmap,但是这个bitmap是一个纯黑色的bitmap。
				// 所以我们在下载图片的时候，一定要先以一个临时文件下载，等下载完毕了，再对图片进行重命名。
				bitmap = BitmapFactory.decodeFileDescriptor(fis.getFD(), null, mOptions);
				
			}
			if (null != bitmap) {//把bitmap转换成drawable
				drawable = new BitmapDrawable(UIUtils.getResources(), bitmap);
			}
			if (drawable != null) {//放到内存缓存队列中
				addDrawableToMemory(url, drawable);
			}
			//如果发生内存溢出，则清楚所有的缓存
		} catch (OutOfMemoryError e) {
			mKeyCache.clear();
			mDrawableCache.clear();
			LogUtils.e(e);
		} catch (Exception e) {
			LogUtils.e(e);
		} finally {
			IOUtils.close(fis);
		}
		return drawable;
	}

	/** 从网络加载图片 */
	private static Drawable loadFromNet(String url) {
		
		HttpResult httpResult = HttpHelper.download(HttpHelper.URL + "image?name=" + url);
		InputStream stream = null;
		if (httpResult == null || (stream = httpResult.getInputStream()) == null) {//请求网络
			return null;
		}
		String tempPath = FileUtils.getIconDir() + url + ".temp";
		String path = FileUtils.getIconDir() + url;
		FileUtils.writeFile(stream, tempPath, true);//把网络下载保存在本地
		if (httpResult != null) {//关闭网络连接
			httpResult.close();
		}
		FileUtils.copy(tempPath, path, true);//进行改名
		return loadFromLocal(url);//从本地加载
	}

	/** 添加到内存 */
	private static void addDrawableToMemory(String url, Drawable drawable) {
		//每次添加的时候都要重新的刷新这个图片的最近访问的时间
		mKeyCache.remove(url);
		//把它从缓存中删除
		mDrawableCache.remove(url);
		//如果大于等于100张，或者图片的总大小大于应用总内存的四分之一先删除前面的
		while (mKeyCache.size() >= MAX_DRAWABLE_COUNT || mTotalSize >= SystemUtils.getOneAppMaxMemory() / 4) {
			//删除存储key的值，返回的是队列最前面的url字符串
			String firstUrl = mKeyCache.remove();
			//从缓存中删除在最前面的图片
			Drawable remove = mDrawableCache.remove(firstUrl);
			//得到删除的缓存图片的大小
			mTotalSize -= DrawableUtils.getDrawableSize(remove);
		}
		mKeyCache.add(url);//添加，添加到最后面
		mDrawableCache.put(url, drawable);//向图片缓存中添加缓存的图片
		mTotalSize += DrawableUtils.getDrawableSize(drawable);//得到图片的大小，重新赋值给TotalSize
	}

	/** 设置给控件图片 */
	private static void setImageSafe(final ImageView view, final String url, final Drawable drawable) {
		if (drawable == null && view.getTag() == null) {
			return;
		}
		UIUtils.runInMainThread(new Runnable() {//需要在主线程中设置
			@Override
			public void run() {
				Object tag;//在主线程中判断，可以做到同步
				if ((tag = view.getTag()) != null) {
					String str = (String) tag;
					if (StringUtils.isEquals(str, url)) {//检测如果url和控件匹配，
						//我下载的比较慢，然后当被复用的时候，很容易显示复用以前的图片，
						//因此设置一个tag，只有设置的tag和当前的url相等的时候才会显示图片
						//只有复用的时候才会出现这种情况
						view.setImageDrawable(drawable);//就进行图片设置
					}
				}
			}
		});
	}
}
