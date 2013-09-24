/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.printspooler;

import android.app.Activity;
import android.app.Dialog;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.print.ILayoutResultCallback;
import android.print.IPrintDocumentAdapter;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnClickListener;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import libcore.io.IoUtils;

/**
 * Activity for configuring a print job.
 */
public class PrintJobConfigActivity extends Activity {

    private static final String LOG_TAG = "PrintJobConfigActivity";

    private static final boolean DEBUG = false;

    public static final String EXTRA_PRINT_DOCUMENT_ADAPTER = "printDocumentAdapter";
    public static final String EXTRA_PRINT_JOB = "printJob";

    public static final String INTENT_EXTRA_PRINTER_ID = "INTENT_EXTRA_PRINTER_ID";

    private static final int LOADER_ID_PRINTERS_LOADER = 1;

    private static final int ORIENTATION_PORTRAIT = 0;
    private static final int ORIENTATION_LANDSCAPE = 1;

    private static final int DEST_ADAPTER_MAX_ITEM_COUNT = 9;

    private static final int DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF = Integer.MAX_VALUE;
    private static final int DEST_ADAPTER_ITEM_ID_ALL_PRINTERS = Integer.MAX_VALUE - 1;

    private static final int ACTIVITY_REQUEST_CREATE_FILE = 1;
    private static final int ACTIVITY_REQUEST_SELECT_PRINTER = 2;

    private static final int CONTROLLER_STATE_FINISHED = 1;
    private static final int CONTROLLER_STATE_FAILED = 2;
    private static final int CONTROLLER_STATE_CANCELLED = 3;
    private static final int CONTROLLER_STATE_INITIALIZED = 4;
    private static final int CONTROLLER_STATE_STARTED = 5;
    private static final int CONTROLLER_STATE_LAYOUT_STARTED = 6;
    private static final int CONTROLLER_STATE_LAYOUT_COMPLETED = 7;
    private static final int CONTROLLER_STATE_WRITE_STARTED = 8;
    private static final int CONTROLLER_STATE_WRITE_COMPLETED = 9;

    private static final int EDITOR_STATE_INITIALIZED = 1;
    private static final int EDITOR_STATE_CONFIRMED_PRINT = 2;
    private static final int EDITOR_STATE_CANCELLED = 3;

    private static final int MIN_COPIES = 1;
    private static final String MIN_COPIES_STRING = String.valueOf(MIN_COPIES);

    private static final Pattern PATTERN_DIGITS = Pattern.compile("[\\d]+");

    private static final Pattern PATTERN_ESCAPE_SPECIAL_CHARS = Pattern.compile(
            "(?=[]\\[+&|!(){}^\"~*?:\\\\])");

    private static final Pattern PATTERN_PAGE_RANGE = Pattern.compile(
            "([0-9]+[\\s]*[\\-]?[\\s]*[0-9]*[\\s]*[,]?[\\s]*)+");

    public static final PageRange[] ALL_PAGES_ARRAY = new PageRange[] {PageRange.ALL_PAGES};

    private final PrintAttributes mOldPrintAttributes = new PrintAttributes.Builder().build();
    private final PrintAttributes mCurrPrintAttributes = new PrintAttributes.Builder().build();

    private final DeathRecipient mDeathRecipient = new DeathRecipient() {
        @Override
        public void binderDied() {
            finish();
        }
    };

    private Editor mEditor;
    private Document mDocument;
    private PrintController mController;

    private PrintJobId mPrintJobId;

    private IBinder mIPrintDocumentAdapter;

    private Dialog mGeneratingPrintJobDialog;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        Bundle extras = getIntent().getExtras();

        PrintJobInfo printJob = extras.getParcelable(EXTRA_PRINT_JOB);
        if (printJob == null) {
            throw new IllegalArgumentException("printJob cannot be null");
        }

        mPrintJobId = printJob.getId();
        mIPrintDocumentAdapter = extras.getBinder(EXTRA_PRINT_DOCUMENT_ADAPTER);
        if (mIPrintDocumentAdapter == null) {
            throw new IllegalArgumentException("PrintDocumentAdapter cannot be null");
        }

        PrintAttributes attributes = printJob.getAttributes();
        if (attributes != null) {
            mCurrPrintAttributes.copyFrom(attributes);
        }

        setContentView(R.layout.print_job_config_activity_container);

        mDocument = new Document();
        mController = new PrintController(new RemotePrintDocumentAdapter(
                IPrintDocumentAdapter.Stub.asInterface(mIPrintDocumentAdapter),
                PrintSpoolerService.peekInstance().generateFileForPrintJob(mPrintJobId)));
        mEditor = new Editor();

        try {
            mIPrintDocumentAdapter.linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException re) {
            finish();
            return;
        }

        mController.initialize();
        mEditor.initialize();
    }

    @Override
    public void onResume() {
        super.onResume();
        mEditor.refreshCurrentPrinter();
    }

    @Override
    protected void onDestroy() {
        // We can safely do the work in here since at this point
        // the system is bound to our (spooler) process which
        // guarantees that this process will not be killed.
        if (mController.hasStarted()) {
            mController.finish();
        }
        if (mEditor.isPrintConfirmed() && mController.isFinished()) {
            PrintSpoolerService.peekInstance().setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_QUEUED, null);
        } else {
            PrintSpoolerService.peekInstance().setPrintJobState(mPrintJobId,
                    PrintJobInfo.STATE_CANCELED, null);
        }
        mIPrintDocumentAdapter.unlinkToDeath(mDeathRecipient, 0);
        if (mGeneratingPrintJobDialog != null) {
            mGeneratingPrintJobDialog.dismiss();
            mGeneratingPrintJobDialog = null;
        }
        super.onDestroy();
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mEditor.isPrintConfirmed() && mEditor.shouldCloseOnTouch(event)) {
            if (!mController.isWorking()) {
                PrintJobConfigActivity.this.finish();
            }
            mEditor.cancel();
            return true;
        }
        return super.onTouchEvent(event);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
        }
        return super.onKeyDown(keyCode, event);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mEditor.isShwoingGeneratingPrintJobUi()) {
                return true;
            }
            if (event.isTracking() && !event.isCanceled()) {
                if (!mController.isWorking()) {
                    PrintJobConfigActivity.this.finish();
                }
            }
            mEditor.cancel();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean printAttributesChanged() {
        return !mOldPrintAttributes.equals(mCurrPrintAttributes);
    }

    private class PrintController {
        private final AtomicInteger mRequestCounter = new AtomicInteger();

        private final RemotePrintDocumentAdapter mRemotePrintAdapter;

        private final Bundle mMetadata;

        private final ControllerHandler mHandler;

        private final LayoutResultCallback mLayoutResultCallback;

        private final WriteResultCallback mWriteResultCallback;

        private int mControllerState = CONTROLLER_STATE_INITIALIZED;

        private boolean mHasStarted;

        private PageRange[] mRequestedPages;

        public PrintController(RemotePrintDocumentAdapter adapter) {
            mRemotePrintAdapter = adapter;
            mMetadata = new Bundle();
            mHandler = new ControllerHandler(getMainLooper());
            mLayoutResultCallback = new LayoutResultCallback(mHandler);
            mWriteResultCallback = new WriteResultCallback(mHandler);
        }

        public void initialize() {
            mHasStarted = false;
            mControllerState = CONTROLLER_STATE_INITIALIZED;
        }

        public void cancel() {
            mControllerState = CONTROLLER_STATE_CANCELLED;
        }

        public boolean isCancelled() {
            return (mControllerState == CONTROLLER_STATE_CANCELLED);
        }

        public boolean isFinished() {
            return (mControllerState == CONTROLLER_STATE_FINISHED);
        }

        public boolean hasStarted() {
            return mHasStarted;
        }

        public boolean hasPerformedLayout() {
            return mControllerState >= CONTROLLER_STATE_LAYOUT_COMPLETED;
        }

        public boolean isWorking() {
            return mControllerState == CONTROLLER_STATE_LAYOUT_STARTED
                    || mControllerState == CONTROLLER_STATE_WRITE_STARTED;
        }

        public void start() {
            mControllerState = CONTROLLER_STATE_STARTED;
            mHasStarted = true;
            mRemotePrintAdapter.start();
        }

        public void update() {
            if (!mController.hasStarted()) {
                mController.start();
            }
            // If print is confirmed we always do a layout since the previous
            // ones were for preview and this one is for printing.
            if (!printAttributesChanged() && !mEditor.isPrintConfirmed()) {
                if (mDocument.info == null) {
                    // We are waiting for the result of a layout, so do nothing.
                    return;
                }
                // If the attributes didn't change and we have done a layout, then
                // we do not do a layout but may have to ask the app to write some
                // pages. Hence, pretend layout completed and nothing changed, so
                // we handle writing as usual.
                handleOnLayoutFinished(mDocument.info, false, mRequestCounter.get());
            } else {
                PrintSpoolerService.peekInstance().setPrintJobAttributesNoPersistence(
                        mPrintJobId, mCurrPrintAttributes);

                mMetadata.putBoolean(PrintDocumentAdapter.EXTRA_PRINT_PREVIEW,
                        !mEditor.isPrintConfirmed());

                mControllerState = CONTROLLER_STATE_LAYOUT_STARTED;

                mRemotePrintAdapter.layout(mOldPrintAttributes, mCurrPrintAttributes,
                        mLayoutResultCallback, mMetadata, mRequestCounter.incrementAndGet());

                mOldPrintAttributes.copyFrom(mCurrPrintAttributes);
            }
        }

        public void finish() {
            mControllerState = CONTROLLER_STATE_FINISHED;
            mRemotePrintAdapter.finish();
        }

        private void handleOnLayoutFinished(PrintDocumentInfo info,
                boolean layoutChanged, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }

            if (isCancelled()) {
                mEditor.updateUi();
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_LAYOUT_COMPLETED;

            // For layout purposes we care only whether the type or the page
            // count changed. We still do not have the size since we did not
            // call write. We use "layoutChanged" set by the application to
            // know whether something else changed about the document.
            final boolean infoChanged = !equalsIgnoreSize(info, mDocument.info);
            // If the info changed, we update the document and the print job.
            if (infoChanged) {
                mDocument.info = info;
                // Set the info.
                PrintSpoolerService.peekInstance().setPrintJobPrintDocumentInfoNoPersistence(
                        mPrintJobId, info);
            }

            // If the document info or the layout changed, then
            // drop the pages since we have to fetch them again.
            if (infoChanged || layoutChanged) {
                mDocument.pages = null;
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(
                        mPrintJobId, null);
            }

            // No pages means that the user selected an invalid range while we
            // were doing a layout or the layout returned a document info for
            // which the selected range is invalid. In such a case we do not
            // write anything and wait for the user to fix the range which will
            // trigger an update.
            mRequestedPages = mEditor.getRequestedPages();
            if (mRequestedPages == null || mRequestedPages.length == 0) {
                mEditor.updateUi();
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            } else {
                // If print is not confirmed we just ask for the first of the
                // selected pages to emulate a behavior that shows preview
                // increasing the chances that apps will implement the APIs
                // correctly.
                if (!mEditor.isPrintConfirmed()) {
                    if (ALL_PAGES_ARRAY.equals(mRequestedPages)) {
                        mRequestedPages = new PageRange[] {new PageRange(0, 0)};
                    } else {
                        final int firstPage = mRequestedPages[0].getStart();
                        mRequestedPages = new PageRange[] {new PageRange(firstPage, firstPage)};
                    }
                }
            }

            // If the info and the layout did not change and we already have
            // the requested pages, then nothing else to do.
            if (!infoChanged && !layoutChanged
                    && PageRangeUtils.contains(mDocument.pages, mRequestedPages)) {
                // Nothing interesting changed and we have all requested pages.
                // Then update the print jobs's pages as we will not do a write
                // and we usually update the pages in the write complete callback.
                updatePrintJobPages(mDocument.pages, mRequestedPages);
                if (mEditor.isDone()) {
                    requestCreatePdfFileOrFinish();
                }
                return;
            }

            mEditor.updateUi();

            // Request a write of the pages of interest.
            mControllerState = CONTROLLER_STATE_WRITE_STARTED;
            mRemotePrintAdapter.write(mRequestedPages, mWriteResultCallback,
                    mRequestCounter.incrementAndGet());
        }

        private void handleOnLayoutFailed(CharSequence error, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }
            mControllerState = CONTROLLER_STATE_FAILED;
            // TODO: We need some UI for announcing an error.
            Log.e(LOG_TAG, "Error during layout: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private void handleOnWriteFinished(PageRange[] pages, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }

            if (isCancelled()) {
                if (mEditor.isDone()) {
                    PrintJobConfigActivity.this.finish();
                }
                return;
            }

            mControllerState = CONTROLLER_STATE_WRITE_COMPLETED;

            // Update the document size.
            File file = PrintSpoolerService.peekInstance()
                    .generateFileForPrintJob(mPrintJobId);
            mDocument.info.setDataSize(file.length());

            // Update the print job with the updated info.
            PrintSpoolerService.peekInstance().setPrintJobPrintDocumentInfoNoPersistence(
                    mPrintJobId, mDocument.info);

            // Update which pages we have fetched.
            mDocument.pages = PageRangeUtils.normalize(pages);

            if (DEBUG) {
                Log.i(LOG_TAG, "Requested: " + Arrays.toString(mRequestedPages)
                        + " and got: " + Arrays.toString(mDocument.pages));
            }

            updatePrintJobPages(mDocument.pages, mRequestedPages);

            if (mEditor.isDone()) {
                requestCreatePdfFileOrFinish();
            }
        }

        private void updatePrintJobPages(PageRange[] writtenPages, PageRange[] requestedPages) {
            // Adjust the print job pages based on what was requested and written.
            // The cases are ordered in the most expected to the least expected.
            if (Arrays.equals(writtenPages, requestedPages)) {
                // We got a document with exactly the pages we wanted. Hence,
                // the printer has to print all pages in the data.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        ALL_PAGES_ARRAY);
            } else if (Arrays.equals(writtenPages, ALL_PAGES_ARRAY)) {
                // We requested specific pages but got all of them. Hence,
                // the printer has to print only the requested pages.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        requestedPages);
            } else if (PageRangeUtils.contains(writtenPages, requestedPages)) {
                // We requested specific pages and got more but not all pages.
                // Hence, we have to offset appropriately the printed pages to
                // be based off the start of the written ones instead of zero.
                // The written pages are always non-null and not empty.
                final int offset = -writtenPages[0].getStart();
                PageRange[] offsetPages = Arrays.copyOf(requestedPages, requestedPages.length);
                PageRangeUtils.offset(offsetPages, offset);
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        offsetPages);
            } else if (Arrays.equals(requestedPages, ALL_PAGES_ARRAY)
                    && writtenPages.length == 1 && writtenPages[0].getStart() == 0
                    && writtenPages[0].getEnd() == mDocument.info.getPageCount() - 1) {
                // We requested all pages via the special constant and got all
                // of them as an explicit enumeration. Hence, the printer has
                // to print only the requested pages.
                PrintSpoolerService.peekInstance().setPrintJobPagesNoPersistence(mPrintJobId,
                        writtenPages);
            } else {
                // We did not get the pages we requested, then the application
                // misbehaves, so we fail quickly.
                // TODO: We need some UI for announcing an error.
                mControllerState = CONTROLLER_STATE_FAILED;
                Log.e(LOG_TAG, "Received invalid pages from the app");
                PrintJobConfigActivity.this.finish();
            }
        }

        private void requestCreatePdfFileOrFinish() {
            if (mEditor.isPrintingToPdf()) {
                PrintJobInfo printJob = PrintSpoolerService.peekInstance()
                        .getPrintJobInfo(mPrintJobId, PrintManager.APP_ID_ANY);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.setType("application/pdf");
                intent.putExtra(Intent.EXTRA_TITLE, printJob.getLabel());
                startActivityForResult(intent, ACTIVITY_REQUEST_CREATE_FILE);
            } else {
                PrintJobConfigActivity.this.finish();
            }
        }

        private void handleOnWriteFailed(CharSequence error, int sequence) {
            if (mRequestCounter.get() != sequence) {
                return;
            }
            mControllerState = CONTROLLER_STATE_FAILED;
            Log.e(LOG_TAG, "Error during write: " + error);
            PrintJobConfigActivity.this.finish();
        }

        private boolean equalsIgnoreSize(PrintDocumentInfo lhs, PrintDocumentInfo rhs) {
            if (lhs == rhs) {
                return true;
            }
            if (lhs == null) {
                if (rhs != null) {
                    return false;
                }
            } else {
                if (rhs == null) {
                    return false;
                }
                if (lhs.getContentType() != rhs.getContentType()
                        || lhs.getPageCount() != rhs.getPageCount()) {
                    return false;
                }
            }
            return true;
        }

        private final class ControllerHandler extends Handler {
            public static final int MSG_ON_LAYOUT_FINISHED = 1;
            public static final int MSG_ON_LAYOUT_FAILED = 2;
            public static final int MSG_ON_WRITE_FINISHED = 3;
            public static final int MSG_ON_WRITE_FAILED = 4;

            public ControllerHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_ON_LAYOUT_FINISHED: {
                        PrintDocumentInfo info = (PrintDocumentInfo) message.obj;
                        final boolean changed = (message.arg1 == 1);
                        final int sequence = message.arg2;
                        handleOnLayoutFinished(info, changed, sequence);
                    } break;

                    case MSG_ON_LAYOUT_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        final int sequence = message.arg1;
                        handleOnLayoutFailed(error, sequence);
                    } break;

                    case MSG_ON_WRITE_FINISHED: {
                        PageRange[] pages = (PageRange[]) message.obj;
                        final int sequence = message.arg1;
                        handleOnWriteFinished(pages, sequence);
                    } break;

                    case MSG_ON_WRITE_FAILED: {
                        CharSequence error = (CharSequence) message.obj;
                        final int sequence = message.arg1;
                        handleOnWriteFailed(error, sequence);
                    } break;
                }
            }
        }
    }

    private static final class LayoutResultCallback extends ILayoutResultCallback.Stub {
        private final WeakReference<PrintController.ControllerHandler> mWeakHandler;

        public LayoutResultCallback(PrintController.ControllerHandler handler) {
            mWeakHandler = new WeakReference<PrintController.ControllerHandler>(handler);
        }

        @Override
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_LAYOUT_FINISHED,
                        changed ? 1 : 0, sequence, info).sendToTarget();
            }
        }

        @Override
        public void onLayoutFailed(CharSequence error, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_LAYOUT_FAILED,
                        sequence, 0, error).sendToTarget();
            }
        }
    }

    private static final class WriteResultCallback extends IWriteResultCallback.Stub {
        private final WeakReference<PrintController.ControllerHandler> mWeakHandler;

        public WriteResultCallback(PrintController.ControllerHandler handler) {
            mWeakHandler = new WeakReference<PrintController.ControllerHandler>(handler);
        }

        @Override
        public void onWriteFinished(PageRange[] pages, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_WRITE_FINISHED,
                        sequence, 0, pages).sendToTarget();
            }
        }

        @Override
        public void onWriteFailed(CharSequence error, int sequence) {
            Handler handler = mWeakHandler.get();
            if (handler != null) {
                handler.obtainMessage(PrintController.ControllerHandler.MSG_ON_WRITE_FAILED,
                    sequence, 0, error).sendToTarget();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case ACTIVITY_REQUEST_CREATE_FILE: {
                if (data != null) {
                    Uri uri = data.getData();
                    writePrintJobDataAndFinish(uri);
                } else {
                    mEditor.showUi(Editor.UI_EDITING_PRINT_JOB,
                            new Runnable() {
                        @Override
                        public void run() {
                            mEditor.initialize();
                            mEditor.bindUi();
                            mEditor.updateUi();
                        }
                    });
                }
            } break;

            case ACTIVITY_REQUEST_SELECT_PRINTER: {
                if (resultCode == RESULT_OK) {
                    PrinterId printerId = (PrinterId) data.getParcelableExtra(
                            INTENT_EXTRA_PRINTER_ID);
                    mEditor.selectPrinter(printerId);
                }
            } break;
        }
    }

    private void writePrintJobDataAndFinish(final Uri uri) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    PrintJobInfo printJob = PrintSpoolerService.peekInstance()
                            .getPrintJobInfo(mPrintJobId, PrintManager.APP_ID_ANY);
                    if (printJob == null) {
                        return null;
                    }
                    File file = PrintSpoolerService.peekInstance()
                            .generateFileForPrintJob(mPrintJobId);
                    in = new FileInputStream(file);
                    out = getContentResolver().openOutputStream(uri);
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            break;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (FileNotFoundException fnfe) {
                    Log.e(LOG_TAG, "Error writing print job data!", fnfe);
                } catch (IOException ioe) {
                    Log.e(LOG_TAG, "Error writing print job data!", ioe);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                }
                return null;
            }

            @Override
            public void onPostExecute(Void result) {
                mEditor.cancel();
                PrintJobConfigActivity.this.finish();
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
    }

    private final class Editor {
        private static final int UI_NONE = 0;
        private static final int UI_EDITING_PRINT_JOB = 1;
        private static final int UI_GENERATING_PRINT_JOB = 2;

        private EditText mCopiesEditText;

        private TextView mRangeOptionsTitle;
        private TextView mPageRangeTitle;
        private EditText mPageRangeEditText;

        private Spinner mDestinationSpinner;
        private final DestinationAdapter mDestinationSpinnerAdapter;

        private Spinner mMediaSizeSpinner;
        private final ArrayAdapter<SpinnerItem<MediaSize>> mMediaSizeSpinnerAdapter;

        private Spinner mColorModeSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mColorModeSpinnerAdapter;

        private Spinner mOrientationSpinner;
        private final  ArrayAdapter<SpinnerItem<Integer>> mOrientationSpinnerAdapter;

        private Spinner mRangeOptionsSpinner;
        private final ArrayAdapter<SpinnerItem<Integer>> mRangeOptionsSpinnerAdapter;

        private final SimpleStringSplitter mStringCommaSplitter =
                new SimpleStringSplitter(',');

        private View mContentContainer;

        private Button mPrintButton;

        private PrinterInfo mCurrentPrinter;

        private boolean mRequestedCurrentPrinterRefresh;

        private final OnItemSelectedListener mOnItemSelectedListener =
                new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> spinner, View view, int position, long id) {
                if (spinner == mDestinationSpinner) {
                    if (mIgnoreNextDestinationChange) {
                        mIgnoreNextDestinationChange = false;
                        return;
                    }

                    if (id == DEST_ADAPTER_ITEM_ID_ALL_PRINTERS) {
                        // The selection changed to the all printers item. We
                        // want to select back the last selected printer.
                        mIgnoreNextDestinationChange = true;
                        mEditor.selectPrinter(mCurrentPrinter.getId());
                        startSelectPrinterActivity();
                        return;
                    }

                    mRequestedCurrentPrinterRefresh = false;

                    mCurrentPrinter = (PrinterInfo) mDestinationSpinnerAdapter
                            .getItem(position);

                    PrintSpoolerService.peekInstance().setPrintJobPrinterNoPersistence(
                            mPrintJobId, mCurrentPrinter);

                    if (mCurrentPrinter.getStatus() == PrinterInfo.STATUS_UNAVAILABLE) {
                        updateUi();
                        return;
                    }

                    PrinterCapabilitiesInfo capabilities = mCurrentPrinter.getCapabilities();
                    if (capabilities == null) {
                        // TODO: We need a timeout for the update.
                        mRequestedCurrentPrinterRefresh = true;
                        updateUi();
                        refreshCurrentPrinter();
                    } else {
                        updatePrintAttributes(capabilities);
                        updateUi();
                        mController.update();
                        refreshCurrentPrinter();
                    }
                } else if (spinner == mMediaSizeSpinner) {
                    if (mOldMediaSizeSelectionIndex
                            == mMediaSizeSpinner.getSelectedItemPosition()) {
                        mOldMediaSizeSelectionIndex = AdapterView.INVALID_POSITION;
                        return;
                    }
                    SpinnerItem<MediaSize> mediaItem = mMediaSizeSpinnerAdapter.getItem(position);
                    mCurrPrintAttributes.setMediaSize(mediaItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mColorModeSpinner) {
                    if (mOldColorModeSelectionIndex
                            == mColorModeSpinner.getSelectedItemPosition()) {
                        mOldColorModeSelectionIndex = AdapterView.INVALID_POSITION;
                        return;
                    }
                    SpinnerItem<Integer> colorModeItem =
                            mColorModeSpinnerAdapter.getItem(position);
                    mCurrPrintAttributes.setColorMode(colorModeItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mOrientationSpinner) {
                    if (mIgnoreNextOrientationChange) {
                        mIgnoreNextOrientationChange = false;
                        return;
                    }
                    SpinnerItem<Integer> orientationItem =
                            mOrientationSpinnerAdapter.getItem(position);
                    setCurrentPrintAttributesOrientation(orientationItem.value);
                    if (!hasErrors()) {
                        mController.update();
                    }
                } else if (spinner == mRangeOptionsSpinner) {
                    if (mIgnoreNextRangeOptionChange) {
                        mIgnoreNextRangeOptionChange = false;
                        return;
                    }
                    updateUi();
                    if (!hasErrors()) {
                        mController.update();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                /* do nothing*/
            }
        };

        private void setCurrentPrintAttributesOrientation(int orientation) {
            MediaSize mediaSize = mCurrPrintAttributes.getMediaSize();
            if (orientation == ORIENTATION_PORTRAIT) {
                if (!mediaSize.isPortrait()) {
                    // Rotate the media size.
                    mCurrPrintAttributes.setMediaSize(mediaSize.asPortrait());

                    // Rotate the resolution.
                    Resolution oldResolution = mCurrPrintAttributes.getResolution();
                    Resolution newResolution = new Resolution(
                            oldResolution.getId(),
                            oldResolution.getLabel(),
                            oldResolution.getVerticalDpi(),
                            oldResolution.getHorizontalDpi());
                    mCurrPrintAttributes.setResolution(newResolution);

                    // Rotate the physical margins.
                    Margins oldMinMargins = mCurrPrintAttributes.getMinMargins();
                    Margins newMinMargins = new Margins(
                            oldMinMargins.getBottomMils(),
                            oldMinMargins.getLeftMils(),
                            oldMinMargins.getTopMils(),
                            oldMinMargins.getRightMils());
                    mCurrPrintAttributes.setMinMargins(newMinMargins);
                }
            } else {
                if (mediaSize.isPortrait()) {
                    // Rotate the media size.
                    mCurrPrintAttributes.setMediaSize(mediaSize.asLandscape());

                    // Rotate the resolution.
                    Resolution oldResolution = mCurrPrintAttributes.getResolution();
                    Resolution newResolution = new Resolution(
                            oldResolution.getId(),
                            oldResolution.getLabel(),
                            oldResolution.getVerticalDpi(),
                            oldResolution.getHorizontalDpi());
                    mCurrPrintAttributes.setResolution(newResolution);

                    // Rotate the physical margins.
                    Margins oldMinMargins = mCurrPrintAttributes.getMinMargins();
                    Margins newMargins = new Margins(
                            oldMinMargins.getTopMils(),
                            oldMinMargins.getRightMils(),
                            oldMinMargins.getBottomMils(),
                            oldMinMargins.getLeftMils());
                    mCurrPrintAttributes.setMinMargins(newMargins);
                }
            }
        }

        private void updatePrintAttributes(PrinterCapabilitiesInfo capabilities) {
            PrintAttributes defaults = capabilities.getDefaults();

            // Media size.
            MediaSize currMediaSize = mCurrPrintAttributes.getMediaSize();
            if (currMediaSize == null) {
                mCurrPrintAttributes.setMediaSize(defaults.getMediaSize());
            } else {
                MediaSize currMediaSizePortrait = currMediaSize.asPortrait();
                List<MediaSize> mediaSizes = capabilities.getMediaSizes();
                final int mediaSizeCount = mediaSizes.size();
                for (int i = 0; i < mediaSizeCount; i++) {
                    MediaSize mediaSize = mediaSizes.get(i);
                    if (currMediaSizePortrait.equals(mediaSize.asPortrait())) {
                        mCurrPrintAttributes.setMediaSize(mediaSize);
                        break;
                    }
                }
            }

            // Color mode.
            final int colorMode = mCurrPrintAttributes.getColorMode();
            if ((capabilities.getColorModes() & colorMode) == 0) {
                mCurrPrintAttributes.setColorMode(colorMode);
            }

            // Resolution
            Resolution resolution = mCurrPrintAttributes.getResolution();
            if (resolution == null || !capabilities.getResolutions().contains(resolution)) {
                mCurrPrintAttributes.setResolution(defaults.getResolution());
            }

            // Margins.
            Margins margins = mCurrPrintAttributes.getMinMargins();
            if (margins == null) {
                mCurrPrintAttributes.setMinMargins(defaults.getMinMargins());
            } else {
                Margins minMargins = capabilities.getMinMargins();
                if (margins.getLeftMils() < minMargins.getLeftMils()
                        || margins.getTopMils() < minMargins.getTopMils()
                        || margins.getRightMils() > minMargins.getRightMils()
                        || margins.getBottomMils() > minMargins.getBottomMils()) {
                    mCurrPrintAttributes.setMinMargins(defaults.getMinMargins());
                }
            }
        }

        private final TextWatcher mCopiesTextWatcher = new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                /* do nothing */
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                /* do nothing */
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (mIgnoreNextCopiesChange) {
                    mIgnoreNextCopiesChange = false;
                    return;
                }

                final boolean hadErrors = hasErrors();

                if (editable.length() == 0) {
                    mCopiesEditText.setError("");
                    updateUi();
                    return;
                }

                int copies = 0;
                try {
                    copies = Integer.parseInt(editable.toString());
                } catch (NumberFormatException nfe) {
                    /* ignore */
                }

                if (copies < MIN_COPIES) {
                    mCopiesEditText.setError("");
                    updateUi();
                    return;
                }

                mCopiesEditText.setError(null);
                PrintSpoolerService.peekInstance().setPrintJobCopiesNoPersistence(
                        mPrintJobId, copies);
                updateUi();

                if (hadErrors && !hasErrors() && printAttributesChanged()) {
                    mController.update();
                }
            }
        };

        private final TextWatcher mRangeTextWatcher = new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                /* do nothing */
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                /* do nothing */
            }

            @Override
            public void afterTextChanged(Editable editable) {
                if (mIgnoreNextRangeChange) {
                    mIgnoreNextRangeChange = false;
                    return;
                }

                final boolean hadErrors = hasErrors();

                String text = editable.toString();

                if (TextUtils.isEmpty(text)) {
                    mPageRangeEditText.setError("");
                    updateUi();
                    return;
                }

                String escapedText = PATTERN_ESCAPE_SPECIAL_CHARS.matcher(text).replaceAll("////");
                if (!PATTERN_PAGE_RANGE.matcher(escapedText).matches()) {
                    mPageRangeEditText.setError("");
                    updateUi();
                    return;
                }

                Matcher matcher = PATTERN_DIGITS.matcher(text);
                while (matcher.find()) {
                    String numericString = text.substring(matcher.start(), matcher.end());
                    final int pageIndex = Integer.parseInt(numericString);
                    if (pageIndex < 1 || pageIndex > mDocument.info.getPageCount()) {
                        mPageRangeEditText.setError("");
                        updateUi();
                        return;
                    }
                }

                //TODO: Catch the error if start is less grater than the end.

                mPageRangeEditText.setError(null);
                mPrintButton.setEnabled(true);
                updateUi();

                if (hadErrors && !hasErrors() && printAttributesChanged()) {
                    updateUi();
                }
            }
        };

        private int mEditorState;

        private boolean mIgnoreNextDestinationChange;
        private int mOldMediaSizeSelectionIndex;
        private int mOldColorModeSelectionIndex;
        private boolean mIgnoreNextOrientationChange;
        private boolean mIgnoreNextRangeOptionChange;
        private boolean mIgnoreNextCopiesChange;
        private boolean mIgnoreNextRangeChange;

        private int mCurrentUi = UI_NONE;

        private boolean mFavoritePrinterSelected;

        public Editor() {
            // Destination.
            mDestinationSpinnerAdapter = new DestinationAdapter();
            mDestinationSpinnerAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    // Initially, we have only safe to PDF as a printer but after some
                    // printers are loaded we want to select the user's favorite one
                    // which is the first.
                    if (!mFavoritePrinterSelected && mDestinationSpinnerAdapter.getCount() > 2) {
                        mFavoritePrinterSelected = true;
                        mDestinationSpinner.setSelection(0);
                    }

                    // If the current printer properties changed, we update the UI.
                    if (mCurrentPrinter != null) {
                        final int printerCount = mDestinationSpinnerAdapter.getCount();
                        for (int i = 0; i < printerCount; i++) {
                            Object item = mDestinationSpinnerAdapter.getItem(i);
                            // Some items are not printers
                            if (item instanceof PrinterInfo) {
                                PrinterInfo printer = (PrinterInfo) item;
                                if (!printer.getId().equals(mCurrentPrinter.getId())) {
                                    continue;
                                }

                                // If the current printer became available and has no
                                // capabilities, we refresh it.
                                if (mCurrentPrinter.getStatus() == PrinterInfo.STATUS_UNAVAILABLE
                                        && printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE
                                        && printer.getCapabilities() == null
                                        && !mRequestedCurrentPrinterRefresh) {
                                    mRequestedCurrentPrinterRefresh = true;
                                    refreshCurrentPrinter();
                                    return;
                                }

                                // We just refreshed the current printer.
                                if (printer.getCapabilities() != null
                                        && mRequestedCurrentPrinterRefresh) {
                                    mRequestedCurrentPrinterRefresh = false;
                                    updatePrintAttributes(printer.getCapabilities());
                                    updateUi();
                                    mController.update();
                                }

                                // Update the UI if capabilities changed.
                                boolean capabilitiesChanged = false;

                                if (mCurrentPrinter.getCapabilities() == null) {
                                    if (printer.getCapabilities() != null) {
                                        capabilitiesChanged = true;
                                    }
                                } else if (!mCurrentPrinter.getCapabilities().equals(
                                        printer.getCapabilities())) {
                                    capabilitiesChanged = true;
                                }

                                // Update the UI if the status changed.
                                final boolean statusChanged = mCurrentPrinter.getStatus()
                                        != printer.getStatus();

                                // Update the printer with the latest info.
                                if (!mCurrentPrinter.equals(printer)) {
                                    mCurrentPrinter.copyFrom(printer);
                                }

                                if (capabilitiesChanged || statusChanged) {
                                    // If something changed during update...
                                    if (updateUi()) {
                                        // Update the document.
                                        mController.update();
                                    }
                                }

                                break;
                            }
                        }
                    }
                }

                @Override
                public void onInvalidated() {
                    /* do nothing - we always have one fake PDF printer */
                }
            });

            // Media size.
            mMediaSizeSpinnerAdapter = new ArrayAdapter<SpinnerItem<MediaSize>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);

            // Color mode.
            mColorModeSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);

            // Orientation
            mOrientationSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            String[] orientationLabels = getResources().getStringArray(
                  R.array.orientation_labels);
            mOrientationSpinnerAdapter.add(new SpinnerItem<Integer>(
                    ORIENTATION_PORTRAIT, orientationLabels[0]));
            mOrientationSpinnerAdapter.add(new SpinnerItem<Integer>(
                    ORIENTATION_LANDSCAPE, orientationLabels[1]));

            // Range options
            mRangeOptionsSpinnerAdapter = new ArrayAdapter<SpinnerItem<Integer>>(
                    PrintJobConfigActivity.this,
                    R.layout.spinner_dropdown_item, R.id.title);
            final int[] rangeOptionsValues = getResources().getIntArray(
                    R.array.page_options_values);
            String[] rangeOptionsLabels = getResources().getStringArray(
                    R.array.page_options_labels);
            final int rangeOptionsCount = rangeOptionsLabels.length;
            for (int i = 0; i < rangeOptionsCount; i++) {
                mRangeOptionsSpinnerAdapter.add(new SpinnerItem<Integer>(
                        rangeOptionsValues[i], rangeOptionsLabels[i]));
            }

            showUi(UI_EDITING_PRINT_JOB, null);
            bindUi();

            mCurrentPrinter = mDestinationSpinnerAdapter.mFakePdfPrinter;
            updatePrintAttributes(mCurrentPrinter.getCapabilities());

            updateUi();
        }

        public void refreshCurrentPrinter() {
            PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
            if (printer != null) {
                FusedPrintersProvider printersLoader = (FusedPrintersProvider)
                        (Loader<?>) getLoaderManager().getLoader(
                                LOADER_ID_PRINTERS_LOADER);
                if (printersLoader != null) {
                    printersLoader.setTrackedPrinter(printer.getId());
                }
            }
        }

        public void addCurrentPrinterToHistory() {
            PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
            PrinterId fakePdfPritnerId = mDestinationSpinnerAdapter.mFakePdfPrinter.getId();
            if (printer != null && !printer.getId().equals(fakePdfPritnerId)) {
                FusedPrintersProvider printersLoader = (FusedPrintersProvider)
                        (Loader<?>) getLoaderManager().getLoader(
                                LOADER_ID_PRINTERS_LOADER);
                if (printersLoader != null) {
                    printersLoader.addHistoricalPrinter(printer);
                }
            }
        }

        public void selectPrinter(PrinterId printerId) {
            mDestinationSpinnerAdapter.ensurePrinterShownPrinterShown(printerId);
            final int position = mDestinationSpinnerAdapter.getPrinterIndex(printerId);
            mDestinationSpinner.setSelection(position);
        }

        public boolean isPrintingToPdf() {
            return mDestinationSpinner.getSelectedItem()
                    == mDestinationSpinnerAdapter.mFakePdfPrinter;
        }

        public boolean shouldCloseOnTouch(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_DOWN) {
                return false;
            }

            final int[] locationInWindow = new int[2];
            mContentContainer.getLocationInWindow(locationInWindow);

            final int windowTouchSlop = ViewConfiguration.get(PrintJobConfigActivity.this)
                    .getScaledWindowTouchSlop();
            final int eventX = (int) event.getX();
            final int eventY = (int) event.getY();
            final int lenientWindowLeft = locationInWindow[0] - windowTouchSlop;
            final int lenientWindowRight = lenientWindowLeft + mContentContainer.getWidth()
                    + windowTouchSlop;
            final int lenientWindowTop = locationInWindow[1] - windowTouchSlop;
            final int lenientWindowBottom = lenientWindowTop + mContentContainer.getHeight()
                    + windowTouchSlop;

            if (eventX < lenientWindowLeft || eventX > lenientWindowRight
                    || eventY < lenientWindowTop || eventY > lenientWindowBottom) {
                return true;
            }
            return false;
        }

        public boolean isShwoingGeneratingPrintJobUi() {
            return (mCurrentUi == UI_GENERATING_PRINT_JOB);
        }

        public void showUi(int ui, final Runnable postSwitchCallback) {
            if (ui == UI_NONE) {
                throw new IllegalStateException("cannot remove the ui");
            }

            if (mCurrentUi == ui) {
                return;
            }

            switch (mCurrentUi) {
                case UI_NONE: {
                    switch (ui) {
                        case UI_EDITING_PRINT_JOB: {
                            doUiSwitch(R.layout.print_job_config_activity_content_editing);
                            registerPrintButtonClickListener();
                            if (postSwitchCallback != null) {
                                postSwitchCallback.run();
                            }
                        } break;

                        case UI_GENERATING_PRINT_JOB: {
                            doUiSwitch(R.layout.print_job_config_activity_content_generating);
                            registerCancelButtonClickListener();
                            if (postSwitchCallback != null) {
                                postSwitchCallback.run();
                            }
                        } break;
                    }
                } break;

                case UI_EDITING_PRINT_JOB: {
                    switch (ui) {
                        case UI_GENERATING_PRINT_JOB: {
                            animateUiSwitch(R.layout.print_job_config_activity_content_generating,
                                    new Runnable() {
                                @Override
                                public void run() {
                                    registerCancelButtonClickListener();
                                    if (postSwitchCallback != null) {
                                        postSwitchCallback.run();
                                    }
                                }
                            });
                        } break;
                    }
                } break;

                case UI_GENERATING_PRINT_JOB: {
                    switch (ui) {
                        case UI_EDITING_PRINT_JOB: {
                            animateUiSwitch(R.layout.print_job_config_activity_content_editing,
                                    new Runnable() {
                                @Override
                                public void run() {
                                    registerPrintButtonClickListener();
                                    if (postSwitchCallback != null) {
                                        postSwitchCallback.run();
                                    }
                                }
                            });
                        } break;
                    }
                } break;
            }

            mCurrentUi = ui;
        }

        private void registerPrintButtonClickListener() {
            Button printButton = (Button) findViewById(R.id.print_button);
            printButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
                    if (printer != null) {
                        mEditor.confirmPrint();
                        mController.update();
                        if (!printer.equals(mDestinationSpinnerAdapter.mFakePdfPrinter)) {
                            mEditor.refreshCurrentPrinter();
                        }
                    } else {
                        mEditor.cancel();
                        PrintJobConfigActivity.this.finish();
                    }
                }
            });
        }

        private void registerCancelButtonClickListener() {
            Button cancelButton = (Button) findViewById(R.id.cancel_button);
            cancelButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mController.isWorking()) {
                        PrintJobConfigActivity.this.finish();
                    }
                    mEditor.cancel();
                }
            });
        }

        private void doUiSwitch(int showLayoutId) {
            ViewGroup contentContainer = (ViewGroup) findViewById(R.id.content_container);
            contentContainer.removeAllViews();
            getLayoutInflater().inflate(showLayoutId, contentContainer, true);
        }

        private void animateUiSwitch(int showLayoutId, final Runnable postAnimateCommand) {
            // Find everything we will shuffle around.
            final ViewGroup contentContainer = (ViewGroup) findViewById(R.id.content_container);
            final View hidingView = contentContainer.getChildAt(0);
            final View showingView = getLayoutInflater().inflate(showLayoutId,
                    null, false);

            // First animation - fade out the old content.
            AutoCancellingAnimator.animate(hidingView).alpha(0.0f)
                    .withLayer().withEndAction(new Runnable() {
                @Override
                public void run() {
                    hidingView.setVisibility(View.INVISIBLE);

                    // Prepare the new content with correct size and alpha.
                    showingView.setMinimumWidth(contentContainer.getWidth());
                    showingView.setAlpha(0.0f);

                    // Compute how to much shrink /stretch the content.
                    final int widthSpec = MeasureSpec.makeMeasureSpec(
                            contentContainer.getWidth(), MeasureSpec.UNSPECIFIED);
                    final int heightSpec = MeasureSpec.makeMeasureSpec(
                            contentContainer.getHeight(), MeasureSpec.UNSPECIFIED);
                    showingView.measure(widthSpec, heightSpec);
                    final float scaleY = (float) showingView.getMeasuredHeight()
                            / (float) contentContainer.getHeight();

                    // Second animation - resize the container.
                    AutoCancellingAnimator.animate(contentContainer).scaleY(scaleY).withLayer()
                            .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            // Swap the old and the new content.
                            contentContainer.removeAllViews();
                            contentContainer.setScaleY(1.0f);
                            contentContainer.addView(showingView);

                            // Third animation - show the new content.
                            AutoCancellingAnimator.animate(showingView).withLayer().alpha(1.0f)
                                    .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    postAnimateCommand.run();
                                }
                            });
                        }
                    });
                }
            });
        }

        public void initialize() {
            mEditorState = EDITOR_STATE_INITIALIZED;
        }

        public boolean isCancelled() {
            return mEditorState == EDITOR_STATE_CANCELLED;
        }

        public void cancel() {
            mEditorState = EDITOR_STATE_CANCELLED;
            mController.cancel();
            updateUi();
        }

        public boolean isDone() {
            return isPrintConfirmed() || isCancelled();
        }

        public boolean isPrintConfirmed() {
            return mEditorState == EDITOR_STATE_CONFIRMED_PRINT;
        }

        public void confirmPrint() {
            addCurrentPrinterToHistory();
            mEditorState = EDITOR_STATE_CONFIRMED_PRINT;
            showUi(UI_GENERATING_PRINT_JOB, null);
        }

        public PageRange[] getRequestedPages() {
            if (hasErrors()) {
                return null;
            }
            if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
                List<PageRange> pageRanges = new ArrayList<PageRange>();
                mStringCommaSplitter.setString(mPageRangeEditText.getText().toString());

                while (mStringCommaSplitter.hasNext()) {
                    String range = mStringCommaSplitter.next().trim();
                    final int dashIndex = range.indexOf('-');
                    final int fromIndex;
                    final int toIndex;

                    if (dashIndex > 0) {
                        fromIndex = Integer.parseInt(range.substring(0, dashIndex)) - 1;
                        // It is possible that the dash is at the end since the input
                        // verification can has to allow the user to keep entering if
                        // this would lead to a valid input. So we handle this.
                        toIndex = (dashIndex < range.length() - 1)
                                ? Integer.parseInt(range.substring(dashIndex + 1,
                                        range.length())) - 1 : fromIndex;
                    } else {
                        fromIndex = toIndex = Integer.parseInt(range) - 1;
                    }

                    PageRange pageRange = new PageRange(Math.min(fromIndex, toIndex),
                            Math.max(fromIndex, toIndex));
                    pageRanges.add(pageRange);
                }

                PageRange[] pageRangesArray = new PageRange[pageRanges.size()];
                pageRanges.toArray(pageRangesArray);

                return PageRangeUtils.normalize(pageRangesArray);
            }

            return ALL_PAGES_ARRAY;
        }

        private void bindUi() {
            if (mCurrentUi != UI_EDITING_PRINT_JOB) {
                return;
            }

            // Content container
            mContentContainer = findViewById(R.id.content_container);

            // Copies
            mCopiesEditText = (EditText) findViewById(R.id.copies_edittext);
            mCopiesEditText.setText(MIN_COPIES_STRING);
            mCopiesEditText.addTextChangedListener(mCopiesTextWatcher);
            mCopiesEditText.selectAll();
            if (!TextUtils.equals(mCopiesEditText.getText(), MIN_COPIES_STRING)) {
                mIgnoreNextCopiesChange = true;
            }
            PrintSpoolerService.peekInstance().setPrintJobCopiesNoPersistence(
                    mPrintJobId, MIN_COPIES);

            // Destination.
            mDestinationSpinner = (Spinner) findViewById(R.id.destination_spinner);
            mDestinationSpinner.setAdapter(mDestinationSpinnerAdapter);
            mDestinationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mDestinationSpinnerAdapter.getCount() > 0 && mController.hasStarted()) {
                mIgnoreNextDestinationChange = true;
            }

            // Media size.
            mMediaSizeSpinner = (Spinner) findViewById(R.id.paper_size_spinner);
            mMediaSizeSpinner.setAdapter(mMediaSizeSpinnerAdapter);
            mMediaSizeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mMediaSizeSpinnerAdapter.getCount() > 0) {
                mOldMediaSizeSelectionIndex = 0;
            }

            // Color mode.
            mColorModeSpinner = (Spinner) findViewById(R.id.color_spinner);
            mColorModeSpinner.setAdapter(mColorModeSpinnerAdapter);
            mColorModeSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mColorModeSpinnerAdapter.getCount() > 0) {
                mOldColorModeSelectionIndex = 0;
            }

            // Orientation
            mOrientationSpinner = (Spinner) findViewById(R.id.orientation_spinner);
            mOrientationSpinner.setAdapter(mOrientationSpinnerAdapter);
            mOrientationSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mOrientationSpinnerAdapter.getCount() > 0) {
                mIgnoreNextOrientationChange = true;
            }

            // Range options
            mRangeOptionsTitle = (TextView) findViewById(R.id.range_options_title);
            mRangeOptionsSpinner = (Spinner) findViewById(R.id.range_options_spinner);
            mRangeOptionsSpinner.setAdapter(mRangeOptionsSpinnerAdapter);
            mRangeOptionsSpinner.setOnItemSelectedListener(mOnItemSelectedListener);
            if (mRangeOptionsSpinnerAdapter.getCount() > 0) {
                mIgnoreNextRangeOptionChange = true;
            }

            // Page range
            mPageRangeTitle = (TextView) findViewById(R.id.page_range_title);
            mPageRangeEditText = (EditText) findViewById(R.id.page_range_edittext);
            mPageRangeEditText.addTextChangedListener(mRangeTextWatcher);

            // Print button
            mPrintButton = (Button) findViewById(R.id.print_button);
            registerPrintButtonClickListener();
        }

        public boolean updateUi() {
            if (mCurrentUi != UI_EDITING_PRINT_JOB) {
                return false;
            }
            if (isPrintConfirmed() || isCancelled()) {
                mDestinationSpinner.setEnabled(false);
                mCopiesEditText.setEnabled(false);
                mMediaSizeSpinner.setEnabled(false);
                mColorModeSpinner.setEnabled(false);
                mOrientationSpinner.setEnabled(false);
                mRangeOptionsSpinner.setEnabled(false);
                mPageRangeEditText.setEnabled(false);
                mPrintButton.setEnabled(false);
                return false;
            }

            // If a printer with capabilities is selected, then we enabled all options.
            boolean allOptionsEnabled = false;
            final int selectedIndex = mDestinationSpinner.getSelectedItemPosition();
            if (selectedIndex >= 0) {
                Object item = mDestinationSpinnerAdapter.getItem(selectedIndex);
                if (item instanceof PrinterInfo) {
                    PrinterInfo printer = (PrinterInfo) item;
                    if (printer.getCapabilities() != null
                            && printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE) {
                        allOptionsEnabled = true;
                    }
                }
            }

            if (!allOptionsEnabled) {
                String minCopiesString = String.valueOf(MIN_COPIES);
                if (!TextUtils.equals(mCopiesEditText.getText(), minCopiesString)) {
                    mIgnoreNextCopiesChange = true;
                    mCopiesEditText.setText(minCopiesString);
                }
                mCopiesEditText.setEnabled(false);

                // Media size
                if (mMediaSizeSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                    mOldMediaSizeSelectionIndex = AdapterView.INVALID_POSITION;
                    mMediaSizeSpinner.setSelection(AdapterView.INVALID_POSITION);
                }
                mMediaSizeSpinner.setEnabled(false);

                // Color mode
                if (mColorModeSpinner.getSelectedItemPosition() != AdapterView.INVALID_POSITION) {
                    mOldColorModeSelectionIndex = AdapterView.INVALID_POSITION;
                    mColorModeSpinner.setSelection(AdapterView.INVALID_POSITION);
                }
                mColorModeSpinner.setEnabled(false);

                // Orientation
                if (mOrientationSpinner.getSelectedItemPosition() != 0) {
                    mIgnoreNextOrientationChange = true;
                    mOrientationSpinner.setSelection(0);
                }
                mOrientationSpinner.setEnabled(false);

                // Range
                if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                    mIgnoreNextRangeOptionChange = true;
                    mRangeOptionsSpinner.setSelection(0);
                }
                mRangeOptionsSpinner.setEnabled(false);
                mRangeOptionsTitle.setText(getString(R.string.label_pages,
                        getString(R.string.page_count_unknown)));
                if (!TextUtils.equals(mPageRangeEditText.getText(), "")) {
                    mIgnoreNextRangeChange = true;
                    mPageRangeEditText.setText("");
                }

                mPageRangeEditText.setEnabled(false);
                mPageRangeEditText.setVisibility(View.INVISIBLE);
                mPageRangeTitle.setVisibility(View.INVISIBLE);

                // Print
                mPrintButton.setEnabled(false);

                return false;
            } else {
                boolean someAttributeSelectionChanged = false;

                PrinterInfo printer = (PrinterInfo) mDestinationSpinner.getSelectedItem();
                PrinterCapabilitiesInfo capabilities = printer.getCapabilities();
                PrintAttributes defaultAttributes = printer.getCapabilities().getDefaults();

                // Media size.
                List<MediaSize> mediaSizes = capabilities.getMediaSizes();

                // If the media sizes changed, we update the adapter and the spinner.
                boolean mediaSizesChanged = false;
                final int mediaSizeCount = mediaSizes.size();
                if (mediaSizeCount != mMediaSizeSpinnerAdapter.getCount()) {
                    mediaSizesChanged = true;
                } else {
                    for (int i = 0; i < mediaSizeCount; i++) {
                        if (!mediaSizes.get(i).equals(mMediaSizeSpinnerAdapter.getItem(i).value)) {
                            mediaSizesChanged = true;
                            break;
                        }
                    }
                }
                if (mediaSizesChanged) {
                    // Remember the old media size to try selecting it again.
                    int oldMediaSizeNewIndex = AdapterView.INVALID_POSITION;
                    MediaSize oldMediaSize = mCurrPrintAttributes.getMediaSize();

                    // Rebuild the adapter data.
                    mMediaSizeSpinnerAdapter.clear();
                    for (int i = 0; i < mediaSizeCount; i++) {
                        MediaSize mediaSize = mediaSizes.get(i);
                        if (mediaSize.equals(oldMediaSize)) {
                            // Update the index of the old selection.
                            oldMediaSizeNewIndex = i;
                        }
                        mMediaSizeSpinnerAdapter.add(new SpinnerItem<MediaSize>(
                                mediaSize, mediaSize.getLabel(getPackageManager())));
                    }

                    mMediaSizeSpinner.setEnabled(true);

                    if (oldMediaSizeNewIndex != AdapterView.INVALID_POSITION) {
                        // Select the old media size - nothing really changed.
                        setMediaSizeSpinnerSelectionNoCallback(oldMediaSizeNewIndex);
                    } else {
                        // Select the first or the default and mark if selection changed.
                        final int mediaSizeIndex = Math.max(mediaSizes.indexOf(
                                defaultAttributes.getMediaSize()), 0);
                        setMediaSizeSpinnerSelectionNoCallback(mediaSizeIndex);
                        mCurrPrintAttributes.setMediaSize(mMediaSizeSpinnerAdapter
                                .getItem(mediaSizeIndex).value);
                        someAttributeSelectionChanged = true;
                    }
                }
                mMediaSizeSpinner.setEnabled(true);

                // Color mode.
                final int colorModes = capabilities.getColorModes();

                // If the color modes changed, we update the adapter and the spinner.
                boolean colorModesChanged = false;
                if (Integer.bitCount(colorModes) != mColorModeSpinnerAdapter.getCount()) {
                    colorModesChanged = true;
                } else {
                    int remainingColorModes = colorModes;
                    int adapterIndex = 0;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        remainingColorModes &= ~colorMode;
                        if (colorMode != mColorModeSpinnerAdapter.getItem(adapterIndex).value) {
                            colorModesChanged = true;
                            break;
                        }
                        adapterIndex++;
                    }
                }
                if (colorModesChanged) {
                    // Remember the old color mode to try selecting it again.
                    int oldColorModeNewIndex = AdapterView.INVALID_POSITION;
                    final int oldColorMode = mCurrPrintAttributes.getColorMode();

                    // Rebuild the adapter data.
                    mColorModeSpinnerAdapter.clear();
                    String[] colorModeLabels = getResources().getStringArray(
                            R.array.color_mode_labels);
                    int remainingColorModes = colorModes;
                    while (remainingColorModes != 0) {
                        final int colorBitOffset = Integer.numberOfTrailingZeros(
                                remainingColorModes);
                        final int colorMode = 1 << colorBitOffset;
                        if (colorMode == oldColorMode) {
                            // Update the index of the old selection.
                            oldColorModeNewIndex = colorBitOffset;
                        }
                        remainingColorModes &= ~colorMode;
                        mColorModeSpinnerAdapter.add(new SpinnerItem<Integer>(colorMode,
                                colorModeLabels[colorBitOffset]));
                    }
                    mColorModeSpinner.setEnabled(true);
                    if (oldColorModeNewIndex != AdapterView.INVALID_POSITION) {
                        // Select the old color mode - nothing really changed.
                        setColorModeSpinnerSelectionNoCallback(oldColorModeNewIndex);
                    } else {
                        final int selectedColorModeIndex = Integer.numberOfTrailingZeros(
                                    (colorModes & defaultAttributes.getColorMode()));
                        setColorModeSpinnerSelectionNoCallback(selectedColorModeIndex);
                        mCurrPrintAttributes.setColorMode(mColorModeSpinnerAdapter
                                .getItem(selectedColorModeIndex).value);
                        someAttributeSelectionChanged = true;
                    }
                }
                mColorModeSpinner.setEnabled(true);

                // Orientation
                MediaSize mediaSize = mCurrPrintAttributes.getMediaSize();
                if (mediaSize.isPortrait()
                        && mOrientationSpinner.getSelectedItemPosition() != 0) {
                    mIgnoreNextOrientationChange = true;
                    mOrientationSpinner.setSelection(0);
                } else if (!mediaSize.isPortrait()
                        && mOrientationSpinner.getSelectedItemPosition() != 1) {
                    mIgnoreNextOrientationChange = true;
                    mOrientationSpinner.setSelection(1);
                }
                mOrientationSpinner.setEnabled(true);

                // Range options
                PrintDocumentInfo info = mDocument.info;
                if (info != null && (info.getPageCount() > 0
                        || info.getPageCount() == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)) {
                    if (info.getPageCount() == 1) {
                        mRangeOptionsSpinner.setEnabled(false);
                    } else {
                        mRangeOptionsSpinner.setEnabled(true);
                        if (mRangeOptionsSpinner.getSelectedItemPosition() > 0) {
                            if (!mPageRangeEditText.isEnabled()) {
                                mPageRangeEditText.setEnabled(true);
                                mPageRangeEditText.setVisibility(View.VISIBLE);
                                mPageRangeTitle.setVisibility(View.VISIBLE);
                                mPageRangeEditText.requestFocus();
                                InputMethodManager imm = (InputMethodManager)
                                        getSystemService(INPUT_METHOD_SERVICE);
                                imm.showSoftInput(mPageRangeEditText, 0);
                            }
                        } else {
                            mPageRangeEditText.setEnabled(false);
                            mPageRangeEditText.setVisibility(View.INVISIBLE);
                            mPageRangeTitle.setVisibility(View.INVISIBLE);
                        }
                    }
                    final int pageCount = mDocument.info.getPageCount();
                    mRangeOptionsTitle.setText(getString(R.string.label_pages,
                            (pageCount == PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                                    ? getString(R.string.page_count_unknown)
                                    : String.valueOf(pageCount)));
                } else {
                    if (mRangeOptionsSpinner.getSelectedItemPosition() != 0) {
                        mIgnoreNextRangeOptionChange = true;
                        mRangeOptionsSpinner.setSelection(0);
                    }
                    mRangeOptionsSpinner.setEnabled(false);
                    mRangeOptionsTitle.setText(getString(R.string.label_pages,
                            getString(R.string.page_count_unknown)));
                    mPageRangeEditText.setEnabled(false);
                    mPageRangeEditText.setVisibility(View.INVISIBLE);
                    mPageRangeTitle.setVisibility(View.INVISIBLE);
                }

                // Print/Print preview
                if (mDestinationSpinner.getSelectedItemId()
                        != DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF) {
                    String newText = getString(R.string.print_button);
                    if (!TextUtils.equals(newText, mPrintButton.getText())) {
                        mPrintButton.setText(R.string.print_button);
                    }
                } else {
                    String newText = getString(R.string.save_button);
                    if (!TextUtils.equals(newText, mPrintButton.getText())) {
                        mPrintButton.setText(R.string.save_button);
                    }
                }
                if ((mRangeOptionsSpinner.getSelectedItemPosition() == 1
                            && (TextUtils.isEmpty(mPageRangeEditText.getText()) || hasErrors()))
                        || (mRangeOptionsSpinner.getSelectedItemPosition() == 0
                            && (!mController.hasPerformedLayout() || hasErrors()))) {
                    mPrintButton.setEnabled(false);
                } else {
                    mPrintButton.setEnabled(true);
                }

                // Copies
                if (mDestinationSpinner.getSelectedItemId()
                        != DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF) {
                    mCopiesEditText.setEnabled(true);
                } else {
                    mCopiesEditText.setEnabled(false);
                }
                if (mCopiesEditText.getError() == null
                        && TextUtils.isEmpty(mCopiesEditText.getText())) {
                    mIgnoreNextCopiesChange = true;
                    mCopiesEditText.setText(String.valueOf(MIN_COPIES));
                    mCopiesEditText.selectAll();
                    mCopiesEditText.requestFocus();
                }

                return someAttributeSelectionChanged;
            }
        }

        private void setMediaSizeSpinnerSelectionNoCallback(int position) {
            if (mMediaSizeSpinner.getSelectedItemPosition() != position) {
                mOldMediaSizeSelectionIndex = position;
                mMediaSizeSpinner.setSelection(position);
            }
        }

        private void setColorModeSpinnerSelectionNoCallback(int position) {
            if (mColorModeSpinner.getSelectedItemPosition() != position) {
                mOldColorModeSelectionIndex = position;
                mColorModeSpinner.setSelection(position);
            }
        }

        private void startSelectPrinterActivity() {
            Intent intent = new Intent(PrintJobConfigActivity.this,
                    SelectPrinterActivity.class);
            startActivityForResult(intent, ACTIVITY_REQUEST_SELECT_PRINTER);
        }

        private boolean hasErrors() {
            if (mCopiesEditText.getError() != null) {
                return true;
            }
            return mPageRangeEditText.getVisibility() == View.VISIBLE
                    && mPageRangeEditText.getError() != null;
        }

        private final class SpinnerItem<T> {
            final T value;
            CharSequence label;

            public SpinnerItem(T value, CharSequence label) {
                this.value = value;
                this.label = label;
            }

            public String toString() {
                return label.toString();
            }
        }

        private final class DestinationAdapter extends BaseAdapter
                implements LoaderManager.LoaderCallbacks<List<PrinterInfo>>{
            private final List<PrinterInfo> mPrinters = new ArrayList<PrinterInfo>();

            private final PrinterInfo mFakePdfPrinter;

            private PrinterId mLastShownPrinterId;

            public DestinationAdapter() {
                getLoaderManager().initLoader(LOADER_ID_PRINTERS_LOADER, null, this);
                mFakePdfPrinter = createFakePdfPrinter();
            }

            public int getPrinterIndex(PrinterId printerId) {
                for (int i = 0; i < getCount(); i++) {
                    PrinterInfo printer = (PrinterInfo) getItem(i);
                    if (printer != null && printer.getId().equals(printerId)) {
                        return i;
                    }
                }
                return AdapterView.INVALID_POSITION;
            }

            public void ensurePrinterShownPrinterShown(PrinterId printerId) {
                mLastShownPrinterId = printerId;
                ensureLastShownPrinterInPosition();
            }

            @Override
            public int getCount() {
                return Math.min(mPrinters.size() + 2, DEST_ADAPTER_MAX_ITEM_COUNT);
            }

            @Override
            public boolean isEnabled(int position) {
                Object item = getItem(position);
                if (item instanceof PrinterInfo) {
                    PrinterInfo printer = (PrinterInfo) item;
                    return printer.getStatus() != PrinterInfo.STATUS_UNAVAILABLE;
                }
                return true;
            }

            @Override
            public Object getItem(int position) {
                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        return mFakePdfPrinter;
                    }
                } else {
                    if (position < 1) {
                        return mPrinters.get(position);
                    }
                    if (position == 1) {
                        return mFakePdfPrinter;
                    }
                    if (position < getCount() - 1) {
                        return mPrinters.get(position - 1);
                    }
                }
                return null;
            }

            @Override
            public long getItemId(int position) {
                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                    }
                    if (position == 1) {
                        return DEST_ADAPTER_ITEM_ID_ALL_PRINTERS;
                    }
                } else {
                    if (position == 1) {
                        return DEST_ADAPTER_ITEM_ID_SAVE_AS_PDF;
                    }
                    if (position == getCount() - 1) {
                        return DEST_ADAPTER_ITEM_ID_ALL_PRINTERS;
                    }
                }
                return position;
            }

            @Override
            public View getDropDownView(int position, View convertView,
                    ViewGroup parent) {
                View view = getView(position, convertView, parent);
                view.setEnabled(isEnabled(position));
                return view;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (convertView == null) {
                    convertView = getLayoutInflater().inflate(
                            R.layout.spinner_dropdown_item, parent, false);
                }

                CharSequence title = null;
                CharSequence subtitle = null;

                if (mPrinters.isEmpty()) {
                    if (position == 0) {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                    } else if (position == 1) {
                        title = getString(R.string.all_printers);
                    }
                } else {
                    if (position == 1) {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                    } else if (position == getCount() - 1) {
                        title = getString(R.string.all_printers);
                    } else {
                        PrinterInfo printer = (PrinterInfo) getItem(position);
                        title = printer.getName();
                        try {
                            PackageInfo packageInfo = getPackageManager().getPackageInfo(
                                    printer.getId().getServiceName().getPackageName(), 0);
                            subtitle = packageInfo.applicationInfo.loadLabel(getPackageManager());
                        } catch (NameNotFoundException nnfe) {
                            /* ignore */
                        }
                    }
                }

                TextView titleView = (TextView) convertView.findViewById(R.id.title);
                titleView.setText(title);

                TextView subtitleView = (TextView) convertView.findViewById(R.id.subtitle);
                if (!TextUtils.isEmpty(subtitle)) {
                    subtitleView.setText(subtitle);
                    subtitleView.setVisibility(View.VISIBLE);
                } else {
                    subtitleView.setText(null);
                    subtitleView.setVisibility(View.GONE);
                }

                return convertView;
            }

            @Override
            public Loader<List<PrinterInfo>> onCreateLoader(int id, Bundle args) {
                if (id == LOADER_ID_PRINTERS_LOADER) {
                    return new FusedPrintersProvider(PrintJobConfigActivity.this);
                }
                return null;
            }

            @Override
            public void onLoadFinished(Loader<List<PrinterInfo>> loader,
                    List<PrinterInfo> printers) {
                mPrinters.clear();
                mPrinters.addAll(printers);
                ensureLastShownPrinterInPosition();
                notifyDataSetChanged();
            }

            @Override
            public void onLoaderReset(Loader<List<PrinterInfo>> loader) {
                mPrinters.clear();
                notifyDataSetInvalidated();
            }

            private void ensureLastShownPrinterInPosition() {
                if (mLastShownPrinterId == null) {
                    return;
                }
                final int printerCount = mPrinters.size();
                for (int i = 0; i < printerCount; i++) {
                    PrinterInfo printer = (PrinterInfo) mPrinters.get(i);
                    if (printer.getId().equals(mLastShownPrinterId)) {
                        // If already in the list - do nothing.
                        if (i < getCount() - 2) {
                            return;
                        }
                        // Else replace the last one.
                        final int lastPrinter = getCount() - 2;
                        mPrinters.set(i, mPrinters.get(lastPrinter - 1));
                        mPrinters.set(lastPrinter - 1, printer);
                        return;
                    }
                }
            }

            private PrinterInfo createFakePdfPrinter() {
                PrinterId printerId = new PrinterId(getComponentName(), "PDF printer");

                PrinterCapabilitiesInfo capabilities =
                        new PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(MediaSize.ISO_A4, true)
                    .addMediaSize(MediaSize.NA_LETTER, false)
                    .addResolution(new Resolution("PDF resolution", "PDF resolution",
                            300, 300), true)
                    .setColorModes(PrintAttributes.COLOR_MODE_COLOR
                            | PrintAttributes.COLOR_MODE_MONOCHROME,
                            PrintAttributes.COLOR_MODE_COLOR)
                    .build();

                return new PrinterInfo.Builder(printerId, getString(R.string.save_as_pdf),
                        PrinterInfo.STATUS_IDLE)
                    .setCapabilities(capabilities)
                    .build();
            }
        }
    }

    /**
     * An instance of this class class is intended to be the first focusable
     * in a layout to which the system automatically gives focus. It performs
     * some voodoo to avoid the first tap on it to start an edit mode, rather
     * to bring up the IME, i.e. to get the behavior as if the view was not
     * focused.
     */
    public static final class CustomEditText extends EditText {
        private boolean mClickedBeforeFocus;
        private CharSequence mError;

        public CustomEditText(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (isFocused() && !mClickedBeforeFocus) {
                clearFocus();
                requestFocus();
            }
            mClickedBeforeFocus = true;
            return true;
        }

        @Override
        public CharSequence getError() {
            return mError;
        }

        @Override
        public void setError(CharSequence error, Drawable icon) {
            setCompoundDrawables(null, null, icon, null);
            mError = error;
        }

        protected void onFocusChanged(boolean gainFocus, int direction,
                Rect previouslyFocusedRect) {
            if (!gainFocus) {
                mClickedBeforeFocus = false;
            }
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        }
    }

    private static final class Document {
        public PrintDocumentInfo info;
        public PageRange[] pages;
    }

    private static final class PageRangeUtils {

        private static final Comparator<PageRange> sComparator = new Comparator<PageRange>() {
            @Override
            public int compare(PageRange lhs, PageRange rhs) {
                return lhs.getStart() - rhs.getStart();
            }
        };

        private PageRangeUtils() {
            throw new UnsupportedOperationException();
        }

        public static boolean contains(PageRange[] ourRanges, PageRange[] otherRanges) {
            if (ourRanges == null || otherRanges == null) {
                return false;
            }

            if (ourRanges.length == 1
                    && PageRange.ALL_PAGES.equals(ourRanges[0])) {
                return true;
            }

            ourRanges = normalize(ourRanges);
            otherRanges = normalize(otherRanges);

            // Note that the code below relies on the ranges being normalized
            // which is they contain monotonically increasing non-intersecting
            // subranges whose start is less that or equal to the end.
            int otherRangeIdx = 0;
            final int ourRangeCount = ourRanges.length;
            final int otherRangeCount = otherRanges.length;
            for (int ourRangeIdx = 0; ourRangeIdx < ourRangeCount; ourRangeIdx++) {
                PageRange ourRange = ourRanges[ourRangeIdx];
                for (; otherRangeIdx < otherRangeCount; otherRangeIdx++) {
                    PageRange otherRange = otherRanges[otherRangeIdx];
                    if (otherRange.getStart() > ourRange.getEnd()) {
                        break;
                    }
                    if (otherRange.getStart() < ourRange.getStart()
                            || otherRange.getEnd() > ourRange.getEnd()) {
                        return false;
                    }
                }
            }
            if (otherRangeIdx < otherRangeCount) {
                return false;
            }
            return true;
        }

        public static PageRange[] normalize(PageRange[] pageRanges) {
            if (pageRanges == null) {
                return null;
            }
            final int oldRangeCount = pageRanges.length;
            if (oldRangeCount <= 1) {
                return pageRanges;
            }
            Arrays.sort(pageRanges, sComparator);
            int newRangeCount = 1;
            for (int i = 0; i < oldRangeCount - 1; i++) {
                newRangeCount++;
                PageRange currentRange = pageRanges[i];
                PageRange nextRange = pageRanges[i + 1];
                if (currentRange.getEnd() + 1 >= nextRange.getStart()) {
                    newRangeCount--;
                    pageRanges[i] = null;
                    pageRanges[i + 1] = new PageRange(currentRange.getStart(),
                            Math.max(currentRange.getEnd(), nextRange.getEnd()));
                }
            }
            if (newRangeCount == oldRangeCount) {
                return pageRanges;
            }
            return Arrays.copyOfRange(pageRanges, oldRangeCount - newRangeCount,
                    oldRangeCount);
        }

        public static void offset(PageRange[] pageRanges, int offset) {
            if (offset == 0) {
                return;
            }
            final int pageRangeCount = pageRanges.length;
            for (int i = 0; i < pageRangeCount; i++) {
                final int start = pageRanges[i].getStart() + offset;
                final int end = pageRanges[i].getEnd() + offset;
                pageRanges[i] = new PageRange(start, end);
            }
        }
    }

    private static final class AutoCancellingAnimator
            implements OnAttachStateChangeListener, Runnable {

        private ViewPropertyAnimator mAnimator;

        private boolean mCancelled;
        private Runnable mEndCallback;

        public static AutoCancellingAnimator animate(View view) {
            ViewPropertyAnimator animator = view.animate();
            AutoCancellingAnimator cancellingWrapper =
                    new AutoCancellingAnimator(animator);
            view.addOnAttachStateChangeListener(cancellingWrapper);
            return cancellingWrapper;
        }

        private AutoCancellingAnimator(ViewPropertyAnimator animator) {
            mAnimator = animator;
        }

        public AutoCancellingAnimator alpha(float alpha) {
            mAnimator = mAnimator.alpha(alpha);
            return this;
        }

        public void cancel() {
            mAnimator.cancel();
        }

        public AutoCancellingAnimator withLayer() {
            mAnimator = mAnimator.withLayer();
            return this;
        }

        public AutoCancellingAnimator withEndAction(Runnable callback) {
            mEndCallback = callback;
            mAnimator = mAnimator.withEndAction(this);
            return this;
        }

        public AutoCancellingAnimator scaleY(float scale) {
            mAnimator = mAnimator.scaleY(scale);
            return this;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            /* do nothing */
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            cancel();
        }

        @Override
        public void run() {
            if (!mCancelled) {
                mEndCallback.run();
            }
        }
    }
}
