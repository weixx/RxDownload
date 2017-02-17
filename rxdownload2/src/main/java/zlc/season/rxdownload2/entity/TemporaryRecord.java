package zlc.season.rxdownload2.entity;

import android.content.Context;

import org.reactivestreams.Publisher;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.functions.Function;
import okhttp3.ResponseBody;
import retrofit2.Response;
import zlc.season.rxdownload2.db.DataBaseHelper;
import zlc.season.rxdownload2.function.DownloadApi;
import zlc.season.rxdownload2.function.FileHelper;

import static android.text.TextUtils.concat;
import static java.io.File.separator;
import static zlc.season.rxdownload2.function.Constant.CACHE;
import static zlc.season.rxdownload2.function.Constant.LMF_SUFFIX;
import static zlc.season.rxdownload2.function.Constant.TMP_SUFFIX;
import static zlc.season.rxdownload2.function.Utils.empty;
import static zlc.season.rxdownload2.function.Utils.mkdirs;

/**
 * Author: Season(ssseasonnn@gmail.com)
 * Date: 2017/2/4
 * FIXME
 */
public class TemporaryRecord {
    private DownloadBean bean;

    private String filePath;
    private String tempPath;
    private String lmfPath;

    private int maxRetryCount;
    private int maxThreads;

    private long contentLength;
    private String lastModify;

    private boolean rangeSupport = false;
    private boolean serverFileChanged = false;

    private DataBaseHelper dataBaseHelper;
    private FileHelper fileHelper;
    private DownloadApi downloadApi;
    private Context context;

    public TemporaryRecord(DownloadBean bean) {
        this.bean = bean;
    }

    /**
     * init needs info
     *
     * @param maxRetryCount   Max retry times
     * @param maxThreads      Max download threads
     * @param defaultSavePath Default save path;
     * @param downloadApi     API
     */
    public void init(Context context, int maxRetryCount, int maxThreads, String defaultSavePath,
                     DownloadApi downloadApi) {
        this.context = context;
        this.maxThreads = maxThreads;
        this.maxRetryCount = maxRetryCount;
        this.downloadApi = downloadApi;
        this.fileHelper = new FileHelper(maxThreads);

        String realSavePath;
        if (empty(bean.getSavePath())) {
            realSavePath = defaultSavePath;
            bean.setSavePath(defaultSavePath);
        } else {
            realSavePath = bean.getSavePath();
        }
        String cachePath = concat(realSavePath, separator, CACHE).toString();

        filePath = concat(realSavePath, separator, bean.getSaveName()).toString();
        tempPath = concat(cachePath, separator, bean.getSaveName(), TMP_SUFFIX).toString();
        lmfPath = concat(cachePath, separator, bean.getSaveName(), LMF_SUFFIX).toString();

        mkdirs(realSavePath, cachePath);
    }

    /**
     * prepare normal download, create files and save last-modify.
     *
     * @throws IOException
     * @throws ParseException
     */
    public void prepareNormalDownload() throws IOException, ParseException {
        fileHelper.prepareDownload(lastModifyFile(), file(), contentLength, lastModify);
    }

    /**
     * prepare range download, create necessary files and save last-modify.
     *
     * @throws IOException
     * @throws ParseException
     */
    public void prepareRangeDownload() throws IOException, ParseException {
        fileHelper.prepareDownload(lastModifyFile(), tempFile(), file(), contentLength, lastModify);
    }

    /**
     * Read download range from record file.
     *
     * @param index index
     * @return
     * @throws IOException
     */
    public DownloadRange readDownloadRange(int index) throws IOException {
        return fileHelper.readDownloadRange(tempFile(), index);
    }

    /**
     * Normal download save.
     *
     * @param e        emitter
     * @param response response
     */
    public void save(FlowableEmitter<DownloadStatus> e, Response<ResponseBody> response) {
        fileHelper.saveFile(e, file(), response);
    }

    /**
     * Range download save
     *
     * @param emitter  emitter
     * @param index    download index
     * @param response response
     * @throws IOException
     */
    public void save(FlowableEmitter<DownloadStatus> emitter, int index, ResponseBody response)
            throws IOException {
        fileHelper.saveFile(emitter, index, tempFile(), file(), response);
    }

    /**
     * Normal download request.
     *
     * @return response
     */
    public Flowable<Response<ResponseBody>> download() {
        return downloadApi.download(null, bean.getUrl());
    }

    /**
     * Range download request
     *
     * @param index download index
     * @return response
     */
    public Flowable<Response<ResponseBody>> rangeDownload(final int index) {
        return Flowable.create(new FlowableOnSubscribe<DownloadRange>() {
            @Override
            public void subscribe(FlowableEmitter<DownloadRange> e) throws Exception {
                DownloadRange range = readDownloadRange(index);
                if (range.legal()) {
                    e.onNext(range);
                }
                e.onComplete();
            }
        }, BackpressureStrategy.ERROR)
                .flatMap(new Function<DownloadRange, Publisher<Response<ResponseBody>>>() {
                    @Override
                    public Publisher<Response<ResponseBody>> apply(DownloadRange range)
                            throws Exception {
                        String rangeStr = "bytes=" + range.start + "-" + range.end;
                        return downloadApi.download(rangeStr, bean.getUrl());
                    }
                });
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public boolean isSupportRange() {
        return rangeSupport;
    }

    public void setRangeSupport(boolean rangeSupport) {
        this.rangeSupport = rangeSupport;
    }

    public boolean isFileChanged() {
        return serverFileChanged;
    }

    public void setFileChanged(boolean serverFileChanged) {
        this.serverFileChanged = serverFileChanged;
    }

    public long getContentLength() {
        return contentLength;
    }

    public void setContentLength(long contentLength) {
        this.contentLength = contentLength;
    }

    public void setLastModify(String lastModify) {
        this.lastModify = lastModify;
    }

    public String getSaveName() {
        return bean.getSaveName();
    }

    public void setSaveName(String saveName) {
        bean.setSaveName(saveName);
    }

    public File file() {
        return new File(filePath);
    }

    public File tempFile() {
        return new File(tempPath);
    }

    public File lastModifyFile() {
        return new File(lmfPath);
    }

    public boolean fileComplete() {
        return file().length() == contentLength;
    }

    public boolean tempFileDamaged() throws IOException {
        return fileHelper.tempFileDamaged(tempFile(), contentLength);
    }

    public String readLastModify() throws IOException {
        return fileHelper.readLastModify(lastModifyFile());
    }

    public boolean fileNotComplete() throws IOException {
        return fileHelper.fileNotComplete(tempFile());
    }

    public File[] getFiles() {
        return new File[]{file(), tempFile(), lastModifyFile()};
    }


    public void start(int type) {
        dataBaseHelper = DataBaseHelper.getSingleton(context.getApplicationContext());
        if (dataBaseHelper.recordNotExists(bean.getUrl())) {
            dataBaseHelper.insertRecord(bean, type);
        }
    }

    public void update(DownloadStatus status) {
        dataBaseHelper.updateRecord(bean.getUrl(), status);
    }

    public void error() {
        dataBaseHelper.updateRecord(bean.getUrl(), DownloadFlag.FAILED);
    }

    public void complete() {
        dataBaseHelper.updateRecord(bean.getUrl(), DownloadFlag.COMPLETED);
    }

    public void cancel() {
        dataBaseHelper.updateRecord(bean.getUrl(), DownloadFlag.PAUSED);
    }

    public void finish() {
        dataBaseHelper.closeDataBase();
    }
}
