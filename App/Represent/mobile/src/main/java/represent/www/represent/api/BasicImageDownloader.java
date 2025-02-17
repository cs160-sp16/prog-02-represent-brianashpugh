package represent.www.represent.api;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

/*
 * From http://stackoverflow.com/questions/15549421/how-to-download-and-save-an-image-in-android
 */

public class BasicImageDownloader {

    private OnImageLoaderListener mImageLoaderListener;
    private Set<String> mUrlsInProgress = new HashSet<>();
    private final String TAG = this.getClass().getSimpleName();

    public BasicImageDownloader(@NonNull OnImageLoaderListener listener) {
        this.mImageLoaderListener = listener;
    }

    public interface OnImageLoaderListener {
        void onError(ImageError error);
        void onProgressChange(int percent);
        void onComplete(Bitmap result, String downloadId);
    }


    public void download(@NonNull final String imageUrl, final String downloadId, final boolean displayProgress) {
        if (mUrlsInProgress.contains(imageUrl)) {
            Log.w(TAG, "a download for this url is already running, " +
                    "no further download will be started");
            return;
        }

        new AsyncTask<Void, Integer, Bitmap>() {

            private ImageError error;

            @Override
            protected void onPreExecute() {
                mUrlsInProgress.add(imageUrl);
                Log.d(TAG, "starting download");
            }

            @Override
            protected void onCancelled() {
                mUrlsInProgress.remove(imageUrl);
                mImageLoaderListener.onError(error);
            }

            @Override
            protected void onProgressUpdate(Integer... values) {
                mImageLoaderListener.onProgressChange(values[0]);
            }

            @Override
            protected Bitmap doInBackground(Void... params) {
                Bitmap bitmap = null;
                HttpURLConnection connection = null;
                InputStream is = null;
                ByteArrayOutputStream out = null;
                try {
                    connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                    if (displayProgress) {
                        connection.connect();
                        final int length = connection.getContentLength();
                        if (length <= 0) {
                            error = new ImageError("Invalid content length. The URL is probably not pointing to a file")
                                    .setErrorCode(ImageError.ERROR_INVALID_FILE);
                            this.cancel(true);
                        }
                        is = new BufferedInputStream(connection.getInputStream(), 8192);
                        out = new ByteArrayOutputStream();
                        byte bytes[] = new byte[8192];
                        int count;
                        long read = 0;
                        while ((count = is.read(bytes)) != -1) {
                            read += count;
                            out.write(bytes, 0, count);
                            publishProgress((int) ((read * 100) / length));
                        }
                        bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
                    } else {
                        is = connection.getInputStream();
                        bitmap = BitmapFactory.decodeStream(is);
                    }
                } catch (Throwable e) {
                    if (!this.isCancelled()) {
                        error = new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION);
                        this.cancel(true);
                    }
                } finally {
                    try {
                        if (connection != null)
                            connection.disconnect();
                        if (out != null) {
                            out.flush();
                            out.close();
                        }
                        if (is != null)
                            is.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                return bitmap;
            }

            @Override
            protected void onPostExecute(Bitmap result) {
                if (result == null) {
                    Log.e(TAG, "factory returned a null result");
                    mImageLoaderListener.onError(new ImageError("downloaded file could not be decoded as bitmap")
                            .setErrorCode(ImageError.ERROR_DECODE_FAILED));
                } else {
                    Log.d(TAG, "download complete, " + result.getByteCount() +
                            " bytes transferred");
                    mImageLoaderListener.onComplete(result, downloadId);
                }
                mUrlsInProgress.remove(imageUrl);
                System.gc();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public interface OnBitmapSaveListener {
        void onBitmapSaved();
        void onBitmapSaveError(ImageError error);
    }


    public static void writeToDisk(@NonNull final File imageFile, @NonNull final Bitmap image,
                                   @NonNull final OnBitmapSaveListener listener,
                                   @NonNull final Bitmap.CompressFormat format, boolean shouldOverwrite) {

        if (imageFile.isFile() && imageFile.exists()) {
            if (!shouldOverwrite) {
                listener.onBitmapSaveError(new ImageError("file already exists, " +
                        "write operation cancelled").setErrorCode(ImageError.ERROR_FILE_EXISTS));
                return;
            } else if (!imageFile.delete()) {
                listener.onBitmapSaveError(new ImageError("could not delete existing file, " +
                        "most likely the write permission was denied")
                        .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
                return;
            }
        }

        if (imageFile.isDirectory()) {
            listener.onBitmapSaveError(new ImageError("the specified path points to a directory, " +
                    "should be a file").setErrorCode(ImageError.ERROR_IS_DIRECTORY));
            return;
        }

        File parent = imageFile.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            listener.onBitmapSaveError(new ImageError("could not create parent directory")
                    .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
            return;
        }

        try {
            if (!imageFile.createNewFile()) {
                listener.onBitmapSaveError(new ImageError("could not create file")
                        .setErrorCode(ImageError.ERROR_PERMISSION_DENIED));
                return;
            }
        } catch (IOException e) {
            listener.onBitmapSaveError(new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION));
            return;
        }

        new AsyncTask<Void, Void, Void>() {

            private ImageError error;

            @Override
            protected Void doInBackground(Void... params) {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(imageFile);
                    image.compress(format, 100, fos);
                } catch (IOException e) {
                    error = new ImageError(e).setErrorCode(ImageError.ERROR_GENERAL_EXCEPTION);
                    this.cancel(true);
                } finally {
                    if (fos != null) {
                        try {
                            fos.flush();
                            fos.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
                return null;
            }

            @Override
            protected void onCancelled() {
                listener.onBitmapSaveError(error);
            }

            @Override
            protected void onPostExecute(Void result) {
                listener.onBitmapSaved();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public static final class ImageError extends Throwable {

        private int errorCode;
        public static final int ERROR_GENERAL_EXCEPTION = -1;
        public static final int ERROR_INVALID_FILE = 0;
        public static final int ERROR_DECODE_FAILED = 1;
        public static final int ERROR_FILE_EXISTS = 2;
        public static final int ERROR_PERMISSION_DENIED = 3;
        public static final int ERROR_IS_DIRECTORY = 4;


        public ImageError(@NonNull String message) {
            super(message);
        }

        public ImageError(@NonNull Throwable error) {
            super(error.getMessage(), error.getCause());
            this.setStackTrace(error.getStackTrace());
        }

        public ImageError setErrorCode(int code) {
            this.errorCode = code;
            return this;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }
}