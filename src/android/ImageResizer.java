package info.protonet.imageresizer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.camera.FileHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public class ImageResizer extends CordovaPlugin {
	private class ResizerOptions {
		String uri;
		String folderName;
		String fileName;
		int quality;
		int width;
		int height;
		
		boolean isFileUri = false;
		boolean base64 = false;
		boolean fit = false;
		boolean fixRotation = false;
	}

	private static final int ARGUMENT_NUMBER = 1;

	private static String UPLOAD_DIR = "/upload-dir";

	public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
		try {
			if (action.equals("resize")) {
				if(!checkParameters(args)){
					callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.INVALID_ACTION));
					return true;
				};

				ResizerOptions options = new ResizerOptions();

				// get the arguments
				JSONObject jsonObject = args.getJSONObject(0);
				options.uri = jsonObject.getString("uri");

				options.isFileUri = !options.uri.startsWith("data");

				options.folderName = null;
				if (jsonObject.has("folderName")) {
					options.folderName = jsonObject.getString("folderName");
				}
				options.fileName = null;
				if (jsonObject.has("fileName")) {
					options.fileName = jsonObject.getString("fileName");
				}

				options.quality = jsonObject.optInt("quality", 85);
				options.width = jsonObject.optInt("width", -1);
				options.height = jsonObject.optInt("height", -1);

				options.base64 = jsonObject.optBoolean("base64", false);
				options.fit = jsonObject.optBoolean("fit", false);
				options.fixRotation = jsonObject.optBoolean("fixRotation",false);

				cordova.getThreadPool().execute(new Runnable() {
					@Override
					public void run() {
						resize(callbackContext, options);
					}
				});

				return true;
			}
		} catch (JSONException e) {
			Log.e("Protonet", "JSON Exception during the Image Resizer Plugin... :(");
			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
			e.printStackTrace();
			return true;
		}
		return false;
	}

	public boolean resize(CallbackContext callbackContext, ResizerOptions options){
		Bitmap bitmap;
		// load the image from uri
		if (options.isFileUri) {
			bitmap = loadScaledBitmapFromUri(options.uri, options.width, options.height, options);

		} else {
			bitmap = this.loadBase64ScaledBitmapFromUri(options.uri, options.width, options.height, options.fit);
		}

		if(bitmap == null){
			Log.e("Protonet", "There was an error reading the image");
			callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
			return false;
		}

		String response;


		// save the image as jpeg on the device
		if (!options.base64) {
			Uri scaledFile = saveFile(bitmap, options);
			response = scaledFile.toString();
			if(scaledFile == null){
				Log.e("Protonet", "There was an error saving the thumbnail");
				callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR));
				return false;
			}
		} else {

			if(options.fixRotation){
				// Get the exif rotation in degrees, create a transformation matrix, and rotate
				// the bitmap
				int rotation = getRoationDegrees(getRotation(options.uri));
				Matrix matrix = new Matrix();
				if (rotation != 0f) {matrix.preRotate(rotation);}
				bitmap = Bitmap.createBitmap(
						bitmap,
						0,
						0,
						bitmap.getWidth(),
						bitmap.getHeight(),
						matrix,
						true);
			}
			
			response =  "data:image/jpeg;base64," + this.getStringImage(bitmap, options.quality);
		}

		bitmap = null;

		callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, response));

		return true;
	}

	public String getStringImage(Bitmap bmp, int quality) {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
		byte[] imageBytes = baos.toByteArray();

		String encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
		return encodedImage;
	}
	private Bitmap loadBase64ScaledBitmapFromUri(String uriString, int width, int height, boolean fit) {
		try {

			String pureBase64Encoded = uriString.substring(uriString.indexOf(",") + 1);
			byte[] decodedBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT);

			Bitmap decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

			int sourceWidth = decodedBitmap.getWidth();
			int sourceHeight = decodedBitmap.getHeight();

			float ratio = sourceWidth > sourceHeight ? ((float) width / sourceWidth) : ((float) height / sourceHeight);

			int execWidth = width > 0 ? width : sourceWidth;
			int execHeigth = height > 0 ? height : sourceHeight;

			if (fit) {
				execWidth = Math.round(ratio * sourceWidth);
				execHeigth = Math.round(ratio * sourceHeight);
			}

			Bitmap scaled = Bitmap.createScaledBitmap(decodedBitmap, execWidth, execHeigth, true);

			decodedBytes = null;
			decodedBitmap = null;

			return scaled;

		} catch (Exception e) {
			Log.e("Protonet", e.toString());
		}
		return null;
	}
	/**
		* Gets the image rotation from the image EXIF Data
		*
		* @param exifOrientation ExifInterface.ORIENTATION_* representation of the rotation
		* @return the rotation in degrees
		*/
	private int getRoationDegrees(int exifOrientation){
		if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) { return 90; }
		else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {  return 180; }
		else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {  return 270; }
		return 0;
	}

	/**
		* Gets the image rotation from the image EXIF Data
		*
		* @param uriString the URI of the image to get the rotation for
		* @return ExifInterface.ORIENTATION_* representation of the rotation
		*/
	private int getRotation(String uriString){
		try {
			ExifInterface exif = new ExifInterface(uriString);
			return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
		} catch (IOException e) {
			return ExifInterface.ORIENTATION_NORMAL;
		}
	}

	/**
		* Loads a Bitmap of the given android uri path
		*
		* @params uri the URI who points to the image
		**/
	private Bitmap loadScaledBitmapFromUri(String uriString, int width, int height, ResizerOptions resizerOptions) {
		try {
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inJustDecodeBounds = true;
			BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uriString, cordova), null, options);

			//calc aspect ratio
			int[] retval = calculateAspectRatio(options.outWidth, options.outHeight, resizerOptions);

			int execWidth = width > 0 ? width : options.outWidth;
			int execHeigth = height > 0 ? height : options.outHeight;

			options.inJustDecodeBounds = false;
			options.inSampleSize = calculateSampleSize(options.outWidth, options.outHeight, execWidth, execHeigth);
			Bitmap unscaledBitmap = BitmapFactory.decodeStream(FileHelper.getInputStreamFromUriString(uriString, cordova), null, options);
			return Bitmap.createScaledBitmap(unscaledBitmap, retval[0], retval[1], true);
		} catch (FileNotFoundException e) {
			Log.e("Protonet", "File not found. :(");
		} catch (IOException e) {
			Log.e("Protonet", "IO Exception :(");
		} catch (Exception e) {
			Log.e("Protonet", e.toString());
		}
		return null;
	}

	private Uri saveFile(Bitmap bitmap, ResizerOptions options) {
		File folder = null;
		String folderName = options.folderName;
		String fileName = options.fileName;
		if (folderName == null) {
			folder = new File(this.getTempDirectoryPath());
		} else {
			if (folderName.contains("/")) {
				folder = new File(folderName.replace("file://", ""));
			} else {
				Context context = this.cordova.getActivity().getApplicationContext();
				folder          = context.getDir(folderName, context.MODE_PRIVATE);
			}
		}
		boolean success = true;
		if (!folder.exists()) {
			success = folder.mkdirs();
		}

		if (success) {
			if (fileName == null) {
				fileName = System.currentTimeMillis() + ".jpg";
			}
			File file = new File(folder, fileName);
			if (file.exists()) file.delete();
			try {
				FileOutputStream out = new FileOutputStream(file);
				bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality, out);
				out.flush();
				out.close();
			} catch (Exception e) {
				Log.e("Protonet", e.toString());
			}

			try{
				if(options.isFileUri){
					String realUri = FileHelper.getRealPath(options.uri, cordova);
					String reaFilelUri = file.getAbsolutePath();
					ExifInterface origExif = new ExifInterface(realUri);
					ExifInterface fileExif = new ExifInterface(reaFilelUri);
					Log.i("DEBUG", String.format("TAG_ORIENTATION=%s", origExif.getAttribute(ExifInterface.TAG_ORIENTATION)));
					fileExif.setAttribute(ExifInterface.TAG_ORIENTATION, origExif.getAttribute(ExifInterface.TAG_ORIENTATION));
					fileExif.saveAttributes();
				}
			} catch(IOException e) {
				Log.e("Protonet", e.toString());
			}

			return Uri.fromFile(file);
		}
		return null;
	}

	/**
		* Figure out what ratio we can load our image into memory at while still being bigger than
		* our desired width and height
		*
		* @param srcWidth
		* @param srcHeight
		* @param dstWidth
		* @param dstHeight
		* @return
		*/
	private int calculateSampleSize(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
		final float srcAspect = (float) srcWidth / (float) srcHeight;
		final float dstAspect = (float) dstWidth / (float) dstHeight;

		if (srcAspect > dstAspect) {
			return srcWidth / dstWidth;
		} else {
			return srcHeight / dstHeight;
		}
	}

	/**
		* Maintain the aspect ratio so the resulting image does not look smooshed
		*
		* @param origWidth
		* @param origHeight
		* @return
		*/
	private int[] calculateAspectRatio(int origWidth, int origHeight, ResizerOptions options) {
		int newWidth = options.width;
		int newHeight = options.height;

		// If no new width or height were specified return the original bitmap
		if (newWidth <= 0 && newHeight <= 0) {
			newWidth = origWidth;
			newHeight = origHeight;
		}
		// Only the width was specified
		else if (newWidth > 0 && newHeight <= 0) {
			newHeight = (newWidth * origHeight) / origWidth;
		}
		// only the height was specified
		else if (newWidth <= 0 && newHeight > 0) {
			newWidth = (newHeight * origWidth) / origHeight;
		}
		// If the user specified both a positive width and height
		// (potentially different aspect ratio) then the width or height is
		// scaled so that the image fits while maintaining aspect ratio.
		// Alternatively, the specified width and height could have been
		// kept and Bitmap.SCALE_TO_FIT specified when scaling, but this
		// would result in whitespace in the new image.
		else {
			double newRatio = newWidth / (double) newHeight;
			double origRatio = origWidth / (double) origHeight;

			if (origRatio > newRatio) {
				newHeight = (newWidth * origHeight) / origWidth;
			} else if (origRatio < newRatio) {
				newWidth = (newHeight * origWidth) / origHeight;
			}
		}

		int[] retval = new int[2];
		retval[0] = newWidth;
		retval[1] = newHeight;
		return retval;
	}

	private boolean checkParameters(JSONArray args) {
		if (args.length() != ARGUMENT_NUMBER) {
			return false;
		}
		return true;
	}

	private String getTempDirectoryPath() {
		String path;

		// SD Card Mounted
		if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) && Environment.isExternalStorageRemovable()) {
			path = Environment.getExternalStorageDirectory().getAbsolutePath() +
					"/Android/data/" + cordova.getActivity().getPackageName() + "/cache"  + UPLOAD_DIR;
		} else {
			// Use internal storage
			path = cordova.getActivity().getCacheDir().getPath() + UPLOAD_DIR;
		}

		// Create the cache directory if it doesn't exist
		File cache = new File(path);
		cache.mkdirs();
		return cache.getAbsolutePath();
	}
}
