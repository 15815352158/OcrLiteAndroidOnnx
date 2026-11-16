#include "CrnnNet.h"
#include "OcrUtils.h"
#include <numeric>
#include <algorithm>

CrnnNet::CrnnNet() {}

CrnnNet::~CrnnNet() {
    delete session;
    inputNamesPtr.clear();
    outputNamesPtr.clear();
}

void CrnnNet::setNumThread(int numOfThread) {
    numThread = numOfThread;
    //===session options===
    // Sets the number of threads used to parallelize the execution within nodes
    // A value of 0 means ORT will pick a default
    sessionOptions.SetIntraOpNumThreads(numThread);
    //set OMP_NUM_THREADS=16

    // Sets the number of threads used to parallelize the execution of the graph (across nodes)
    // If sequential execution is enabled this value is ignored
    // A value of 0 means ORT will pick a default
    sessionOptions.SetInterOpNumThreads(numThread);

    // Sets graph optimization level
    // ORT_DISABLE_ALL -> To disable all optimizations
    // ORT_ENABLE_BASIC -> To enable basic optimizations (Such as redundant node removals)
    // ORT_ENABLE_EXTENDED -> To enable extended optimizations (Includes level 1 + more complex optimizations like node fusions)
    // ORT_ENABLE_ALL -> To Enable All possible opitmizations
    sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);
}

char *readKeysFromAssets(AAssetManager *mgr) {
    //LOGI("readKeysFromAssets start...");
    if (mgr == NULL) {
        LOGE(" %s", "AAssetManager==NULL");
        return NULL;
    }
    char *buffer;
    /*获取文件名并打开*/
    AAsset *asset = AAssetManager_open(mgr, "keys.txt", AASSET_MODE_UNKNOWN);
    if (asset == NULL) {
        LOGE(" %s", "asset==NULL");
        return NULL;
    }
    /*获取文件大小*/
    off_t bufferSize = AAsset_getLength(asset);
    //LOGI("file size : %d", bufferSize);
    buffer = (char *) malloc(bufferSize + 1);
    buffer[bufferSize] = 0;
    int numBytesRead = AAsset_read(asset, buffer, bufferSize);
    //LOGI("readKeysFromAssets: %d", numBytesRead);
    /*关闭文件*/
    AAsset_close(asset);
    //LOGI("readKeysFromAssets exit...");
    return buffer;
}

bool CrnnNet::initModel(AAssetManager *mgr) {
    int dbModelDataLength = 0;
    void *dbModelData = getModelDataFromAssets(mgr, "crnn_lite_lstm.onnx", dbModelDataLength);
    session = new Ort::Session(ortEnv, dbModelData, dbModelDataLength,
                               sessionOptions);
    free(dbModelData);
    inputNamesPtr = getInputNames(session);
    outputNamesPtr = getOutputNames(session);

    //load keys
    char *buffer = readKeysFromAssets(mgr);
    if (buffer != NULL) {
        std::istringstream inStr(buffer);
        std::string line;
        int size = 0;
        while (getline(inStr, line)) {
            keys.emplace_back(line);
            size++;
        }
        free(buffer);
        LOGI("keys size(%d)", size);
    } else {
        LOGE(" txt file not found");
        return false;
    }

    return true;
}

TextLine CrnnNet::scoreToTextLine(const float *srcData, int h, int w, const cv::Mat &srcResize) {
    int lastIndex = 0;
    int keySize = keys.size();
    std::vector<float> scores;
    // 收集有效字符及其 CTC 时间步（时间步近似对应字符在图片中的水平位置）
    std::vector<std::pair<std::string, int>> charSeq;
    for (int i = 0; i < h; i++) {
        int maxIndex = 0;
        float maxValue = -1000.f;
        //do softmax
        std::vector<float> exps(w);
        for (int j = 0; j < w; j++) {
            float expSingle = exp(srcData[i * w + j]);
            exps.at(j) = expSingle;
        }
        float partition = accumulate(exps.begin(), exps.end(), 0.0);//row sum
        for (int j = 0; j < w; j++) {
            float softmax = exps[j] / partition;
            if (softmax > maxValue) {
                maxValue = softmax;
                maxIndex = j;
            }
        }

        //no softmax
        /*for (int j = 0; j < w; j++) {
            if (srcData[i * w + j] > maxValue) {
                maxValue = srcData[i * w + j];
                maxIndex = j;
            }
        }*/
        if (maxIndex > 0 && maxIndex < keySize && (!(i > 0 && maxIndex == lastIndex))) {
            scores.emplace_back(maxValue);
            charSeq.emplace_back(keys[maxIndex - 1], i);
        }
        lastIndex = maxIndex;
    }
    std::string strRes;
    if (charSeq.size() <= 1) {
        if (!charSeq.empty()) {
            strRes = charSeq[0].first;
        }
        return {strRes, scores};
    }
    // 识别字典几乎不含空格，字间空白在 CTC 输出里会丢失，需按行图像列空白还原。
    // 校验码等数字串按"5位一组、组间宽空格"印刷：组内间隙小且均匀、组间间隙大。
    // 判定规则：边界空白需同时大于 [行内空白中位数的2倍] 与 [平均字距的25%]，
    // 只在组间插入空格，避免把组内数字逐位拆散。
    cv::Mat gray;
    if (srcResize.channels() == 3) {
        cv::cvtColor(srcResize, gray, cv::COLOR_BGR2GRAY);
    } else {
        gray = srcResize;
    }
    cv::Mat bw;
    cv::threshold(gray, bw, 0, 255, cv::THRESH_BINARY_INV | cv::THRESH_OTSU);
    int W = srcResize.cols;
    std::vector<int> ink(W, 0);
    for (int x = 0; x < W; x++) {
        int s = 0;
        for (int y = 0; y < bw.rows; y++) {
            if (bw.at<uchar>(y, x) > 0) {
                s++;
            }
        }
        ink[x] = s;
    }
    size_t n = charSeq.size();
    float pitch = (float) W / (float) n;
    // 统计每个字符边界中点附近窗口内的最大连续空白列宽
    std::vector<int> blanks(n - 1);
    for (size_t k = 1; k < n; k++) {
        int center = (int) (((float) (charSeq[k].second + charSeq[k - 1].second) / 2.0f /
                             (float) h) * W);
        int x0 = std::max(0, center - (int) (pitch * 0.8f));
        int x1 = std::min(W - 1, center + (int) (pitch * 0.8f));
        int best = 0, cur = 0;
        for (int x = x0; x <= x1; x++) {
            if (ink[x] == 0) {
                cur++;
                if (cur > best) {
                    best = cur;
                }
            } else {
                cur = 0;
            }
        }
        blanks[k - 1] = best;
    }
    std::vector<int> sortedBlanks(blanks);
    std::sort(sortedBlanks.begin(), sortedBlanks.end());
    int medBlank = sortedBlanks[sortedBlanks.size() / 2];
    auto isDigitChar = [](const std::string &s) {
        return s.size() == 1 && s[0] >= '0' && s[0] <= '9';
    };
    strRes.append(charSeq[0].first);
    int spaceCount = 0;
    for (size_t k = 1; k < n; k++) {
        // 两侧都是数字时提高阈值到 50% 字距：密集印刷的连续号码（组内间隙约15%）绝不拆断，
        // 而"5位一组"的组间空格（约40-60%字距）仍能识别；非数字边界维持 25%
        bool bothDigits = isDigitChar(charSeq[k - 1].first) && isDigitChar(charSeq[k].first);
        int spaceThresh = std::max(medBlank * 2,
                                   (int) (pitch * (bothDigits ? 0.5f : 0.25f)));
        if (blanks[k - 1] > spaceThresh) {
            strRes.append(" ");
            spaceCount++;
        }
        strRes.append(charSeq[k].first);
    }
    if (spaceCount > 0) {
        LOGI("restore %d space(s), pitch(%.1f), medBlank(%d), text(%s)",
             spaceCount, pitch, medBlank, strRes.c_str());
    }
    return {strRes, scores};
}

TextLine CrnnNet::getTextLine(cv::Mat &src) {
    float scale = (float) dstHeight / (float) src.rows;
    int dstWidth = int((float) src.cols * scale);

    cv::Mat srcResize;
    resize(src, srcResize, cv::Size(dstWidth, dstHeight));

    std::vector<float> inputTensorValues = substractMeanNormalize(srcResize, meanValues, normValues);

    std::array<int64_t, 4> inputShape{1, srcResize.channels(), srcResize.rows, srcResize.cols};

    auto memoryInfo = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeCPU);


    Ort::Value inputTensor = Ort::Value::CreateTensor<float>(memoryInfo, inputTensorValues.data(),
                                                             inputTensorValues.size(), inputShape.data(),
                                                             inputShape.size());
    assert(inputTensor.IsTensor());
    std::vector<const char *> inputNames = {inputNamesPtr.data()->get()};
    std::vector<const char *> outputNames = {outputNamesPtr.data()->get()};
    auto outputTensor = session->Run(Ort::RunOptions{nullptr}, inputNames.data(), &inputTensor,
                                     inputNames.size(), outputNames.data(), outputNames.size());

    assert(outputTensor.size() == 1 && outputTensor.front().IsTensor());

    size_t count = outputTensor.front().GetTensorTypeAndShapeInfo().GetElementCount();
    size_t rows = count / crnnCols;
    float *floatArray = outputTensor.front().GetTensorMutableData<float>();

    cv::Mat score(rows, crnnCols, CV_32FC1);
    memcpy(score.data, floatArray, rows * crnnCols * sizeof(float));

    return scoreToTextLine((float *) score.data, rows, crnnCols, srcResize);
}

std::vector<TextLine> CrnnNet::getTextLines(std::vector<cv::Mat> &partImg) {
    int size = partImg.size();
    std::vector<TextLine> textLines(size);
    for (int i = 0; i < size; ++i) {
        //getTextLine
        double startCrnnTime = getCurrentTime();
        TextLine textLine = getTextLine(partImg[i]);
        double endCrnnTime = getCurrentTime();
        textLine.time = endCrnnTime - startCrnnTime;

        //Log textLine
        //Logger("textLine[%d](%s)", i, textLine.text.c_str());
        textLines[i] = textLine;

        /*std::ostringstream txtScores;
        for (int s = 0; s < textLine.charScores.size(); ++s) {
            if (s == 0) {
                txtScores << textLine.charScores[s];
            } else {
                txtScores << " ," << textLine.charScores[s];
            }
        }*/
        //Logger("textScores[%d]{%s}", i, string(txtScores.str()).c_str());
        //Logger("crnnTime[%d](%fms)", i, textLine.time);
    }
    return textLines;
}