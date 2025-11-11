#include <jni.h>
#include <string>

#include <vector>
#include <algorithm>
#include <chrono>

#include <iostream>
#include <cassert>
#include <openfhe.h>

#include <ciphertext-ser.h>
#include <cryptocontext-ser.h>
#include <key/key-ser.h>
#include <scheme/bfvrns/bfvrns-ser.h>

#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

#define LOG_TAG "FHENativeModule"

const std::string KEYS_FOLDER = "keys/";
const std::string CRYPTO_CONTEXT_FILE = KEYS_FOLDER + "crypto_context.bin";
const std::string PUBLIC_KEY_FILE = KEYS_FOLDER + "key_pub.bin";
const std::string SECRET_KEY_FILE = KEYS_FOLDER + "key_priv.bin";

lbcrypto::CryptoContext<lbcrypto::DCRTPoly> cryptoContext = NULL;
lbcrypto::PrivateKey<lbcrypto::DCRTPoly> secretKey;
lbcrypto::PublicKey<lbcrypto::DCRTPoly> publicKey;

// adb shell ls -l "/sdcard/Android/data/com.moravio.openfhe/files/keys"
// adb pull "/sdcard/Android/data/com.moravio.openfhe/files/keys" ./keys_dump

void createCryptoContext(JNIEnv* env, jclass clazz, jstring outDirJ)
{
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "initialize new crypto context and keys");

    const char* outDirC = env->GetStringUTFChars(outDirJ, nullptr);
    std::string outDir(outDirC ? outDirC : "");
    env->ReleaseStringUTFChars(outDirJ, outDirC);

    if (outDir.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Output directory is empty");
        return;
    }

    lbcrypto::CCParams<lbcrypto::CryptoContextCKKSRNS> parameters;
    parameters.SetMultiplicativeDepth(0);
    parameters.SetSecurityLevel(lbcrypto::HEStd_128_quantum);
    parameters.SetScalingModSize(42);
    parameters.SetSecretKeyDist(lbcrypto::UNIFORM_TERNARY);
    parameters.SetFirstModSize(parameters.GetScalingModSize() + 1);
    parameters.SetKeySwitchTechnique(lbcrypto::BV);
    parameters.SetScalingTechnique(lbcrypto::FLEXIBLEAUTO);
    parameters.SetRingDim(1 << 11);
    parameters.SetDesiredPrecision(24);

    cryptoContext = lbcrypto::GenCryptoContext(parameters);

    cryptoContext->Enable(lbcrypto::PKE);
    cryptoContext->Enable(lbcrypto::KEYSWITCH);
    cryptoContext->Enable(lbcrypto::LEVELEDSHE);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Cryptocontext generated");

    lbcrypto::KeyPair<lbcrypto::DCRTPoly> kp = cryptoContext->KeyGen();

    const std::string ctxPath = outDir + "/crypto_context.bin";
    const std::string pubPath = outDir + "/key_pub.bin";
    const std::string secPath = outDir + "/key_priv.bin";

    if (!lbcrypto::Serial::SerializeToFile(ctxPath, cryptoContext, lbcrypto::SerType::BINARY)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to write %s", ctxPath.c_str());
        return;
    }

    if (!lbcrypto::Serial::SerializeToFile(pubPath, kp.publicKey, lbcrypto::SerType::BINARY)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to write %s", pubPath.c_str());
        return;
    }
    if (!lbcrypto::Serial::SerializeToFile(secPath, kp.secretKey, lbcrypto::SerType::BINARY)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Failed to write %s", secPath.c_str());
        return;
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Keypair generated");
}

static std::istringstream assetToStream(AAssetManager* mgr, const char* path) {
    AAsset* a = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);

    if (!a) return std::istringstream{};

    const void* buf = AAsset_getBuffer(a);
    const off_t len = AAsset_getLength(a);

    std::string s(static_cast<const char*>(buf), static_cast<size_t>(len));
    AAsset_close(a);

    return std::istringstream(std::move(s));
}

void loadKeys(JNIEnv* env, jclass clazz, jobject assetManager)
{
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    if (mgr == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "AAssetManager is null");
        return;
    }

    if (!cryptoContext)
    {
        cryptoContext->ClearEvalMultKeys();
        cryptoContext->ClearEvalAutomorphismKeys();
        lbcrypto::CryptoContextFactory<lbcrypto::DCRTPoly>::ReleaseAllContexts();

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading crypto context...");

        auto ctxStream = assetToStream(mgr, CRYPTO_CONTEXT_FILE.c_str());

        lbcrypto::Serial::Deserialize(cryptoContext, ctxStream, lbcrypto::SerType::BINARY);

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Crypto Context loaded");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading public key");

        auto pubKeyStream = assetToStream(mgr, PUBLIC_KEY_FILE.c_str());

        lbcrypto::Serial::Deserialize(publicKey, pubKeyStream, lbcrypto::SerType::BINARY);

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Public key loaded");

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Loading secret key");

        auto secretKeyStream = assetToStream(mgr, SECRET_KEY_FILE.c_str());

        lbcrypto::Serial::Deserialize(secretKey, secretKeyStream, lbcrypto::SerType::BINARY);

        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Secret key loaded");
    }
}

std::vector<double> convertToDouble(const std::vector<float> &floatVec)
{
    std::vector<double> doubleVec;
    doubleVec.reserve(floatVec.size());
    std::transform(
            floatVec.begin(),
            floatVec.end(),
            std::back_inserter(doubleVec),
            [](float val){ return static_cast<double>(val); }
    );

    return doubleVec;
}

jbyteArray encrypt(JNIEnv* env, jclass clazz, jfloatArray inputData)
{
    jsize length = env->GetArrayLength(inputData);
    jfloat* bytes = env->GetFloatArrayElements(inputData, nullptr);

    std::vector<float> cppVector;
    cppVector.reserve(length / sizeof(float));
    const float* floatData = reinterpret_cast<const float*>(bytes);

    for (jsize i = 0; i < length / sizeof(float); ++i) {
        cppVector.push_back(floatData[i]);
    }

    env->ReleaseFloatArrayElements(inputData, bytes, JNI_ABORT);

    lbcrypto::Plaintext plaintext = cryptoContext->MakeCKKSPackedPlaintext(convertToDouble(cppVector));

    auto encrypted = cryptoContext->Encrypt(publicKey, plaintext);

    std::ostringstream oss;
    lbcrypto::Serial::Serialize(encrypted, oss, lbcrypto::SerType::BINARY);
    std::string encryptedStr = oss.str();

    jbyteArray result = env->NewByteArray(encryptedStr.size());
    env->SetByteArrayRegion(result, 0, encryptedStr.size(), reinterpret_cast<const jbyte*>(encryptedStr.data()));

    return result;
}

jfloatArray decrypt(JNIEnv* env, jclass clazz, jbyteArray encryptedData)
{
    jsize length = env->GetArrayLength(encryptedData);
    jbyte* bytes = env->GetByteArrayElements(encryptedData, nullptr);

    std::string encryptedStr(reinterpret_cast<const char*>(bytes), length);
    env->ReleaseByteArrayElements(encryptedData, bytes, JNI_ABORT);

    std::istringstream iss(encryptedStr);
    lbcrypto::Ciphertext<lbcrypto::DCRTPoly> ciphertext;
    lbcrypto::Serial::Deserialize(ciphertext, iss, lbcrypto::SerType::BINARY);

    lbcrypto::Plaintext plaintext;
    cryptoContext->Decrypt(secretKey, ciphertext, &plaintext);

    plaintext->SetLength(plaintext->GetLength());
    std::vector<double> decoded = plaintext->GetRealPackedValue();

    jfloatArray result = env->NewFloatArray(decoded.size());
    std::vector<jfloat> floatVec(decoded.begin(), decoded.end());
    env->SetFloatArrayRegion(result, 0, decoded.size(), floatVec.data());

    return result;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Find your class. JNI_OnLoad is called from the correct class loader context for this to work.
    jclass c = env->FindClass("org/thoughtcrime/securesms/components/webrtc/fhe/FHEService");
    if (c == nullptr) return JNI_ERR;

    // Register your class' native methods.
    static const JNINativeMethod methods[] = {
            {"loadKeys", "(Landroid/content/res/AssetManager;)V", reinterpret_cast<void*>(loadKeys)},
            {"createCryptoContext", "(Ljava/lang/String;)V", reinterpret_cast<void*>(createCryptoContext)},
            {"encrypt", "([F)[B", reinterpret_cast<void*>(encrypt)},
            {"decrypt", "([B)[F", reinterpret_cast<void*>(decrypt)},
    };

    int rc = env->RegisterNatives(c, methods, sizeof(methods)/sizeof(JNINativeMethod));
    if (rc != JNI_OK) return rc;

    return JNI_VERSION_1_6;
}