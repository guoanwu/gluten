/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma once

#include <arrow/ipc/reader.h>
#include <arrow/ipc/writer.h>
#include <arrow/util/parallel.h>
#include <jni.h>

#include "compute/ProtobufUtils.h"
#include "config/GlutenConfig.h"
#include "memory/ArrowMemoryPool.h"
#include "utils/compression.h"
#include "utils/exception.h"

static jint jniVersion = JNI_VERSION_1_8;

static inline jclass createGlobalClassReference(JNIEnv* env, const char* className) {
  jclass localClass = env->FindClass(className);
  jclass globalClass = (jclass)env->NewGlobalRef(localClass);
  env->DeleteLocalRef(localClass);
  return globalClass;
}

static inline jmethodID getMethodId(JNIEnv* env, jclass thisClass, const char* name, const char* sig) {
  jmethodID ret = env->GetMethodID(thisClass, name, sig);
  return ret;
}

static inline jmethodID getStaticMethodId(JNIEnv* env, jclass thisClass, const char* name, const char* sig) {
  jmethodID ret = env->GetStaticMethodID(thisClass, name, sig);
  return ret;
}

static inline jmethodID getStaticMethodIdOrError(JNIEnv* env, jclass thisClass, const char* name, const char* sig) {
  jmethodID ret = getStaticMethodId(env, thisClass, name, sig);
  if (ret == nullptr) {
    std::string errorMessage =
        "Unable to find static method " + std::string(name) + " within signature" + std::string(sig);
    throw gluten::GlutenException(errorMessage);
  }
  return ret;
}

static inline std::string jStringToCString(JNIEnv* env, jstring string) {
  int32_t jlen, clen;
  clen = env->GetStringUTFLength(string);
  jlen = env->GetStringLength(string);
  char buffer[clen];
  env->GetStringUTFRegion(string, 0, jlen, buffer);
  return std::string(buffer, clen);
}

static inline arrow::Compression::type getCompressionType(JNIEnv* env, jstring codecJstr) {
  if (codecJstr == NULL) {
    return arrow::Compression::UNCOMPRESSED;
  }
  auto codecU = env->GetStringUTFChars(codecJstr, JNI_FALSE);

  std::string codecL;
  std::transform(codecU, codecU + std::strlen(codecU), std::back_inserter(codecL), ::tolower);

  GLUTEN_ASSIGN_OR_THROW(auto compression_type, arrow::util::Codec::GetCompressionType(codecL));

  if (compression_type == arrow::Compression::LZ4) {
    compression_type = arrow::Compression::LZ4_FRAME;
  }
  env->ReleaseStringUTFChars(codecJstr, codecU);
  return compression_type;
}

static inline gluten::CodecBackend getCodecBackend(JNIEnv* env, jstring codecJstr) {
  if (codecJstr == nullptr) {
    return gluten::CodecBackend::NONE;
  }
  auto codecBackend = jStringToCString(env, codecJstr);
  if (codecBackend == "qat") {
    return gluten::CodecBackend::QAT;
  } else if (codecBackend == "iaa") {
    return gluten::CodecBackend::IAA;
  } else {
    throw std::invalid_argument("Not support this codec backend " + codecBackend);
  }
}

static inline void attachCurrentThreadAsDaemonOrThrow(JavaVM* vm, JNIEnv** out) {
  int getEnvStat = vm->GetEnv(reinterpret_cast<void**>(out), jniVersion);
  if (getEnvStat == JNI_EDETACHED) {
#ifdef GLUTEN_PRINT_DEBUG
    std::cout << "JNIEnv was not attached to current thread." << std::endl;
#endif
    // Reattach current thread to JVM
    getEnvStat = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(out), NULL);
    if (getEnvStat != JNI_OK) {
      throw gluten::GlutenException("Failed to reattach current thread to JVM.");
    }
#ifdef GLUTEN_PRINT_DEBUG
    std::cout << "Succeeded attaching current thread." << std::endl;
#endif
    return;
  }
  if (getEnvStat != JNI_OK) {
    throw gluten::GlutenException("Failed to attach current thread to JVM.");
  }
}

static inline void checkException(JNIEnv* env) {
  if (env->ExceptionCheck()) {
    jthrowable t = env->ExceptionOccurred();
    env->ExceptionClear();
    jclass describerClass = env->FindClass("io/glutenproject/exception/JniExceptionDescriber");
    jmethodID describeMethod =
        env->GetStaticMethodID(describerClass, "describe", "(Ljava/lang/Throwable;)Ljava/lang/String;");
    std::string description =
        jStringToCString(env, (jstring)env->CallStaticObjectMethod(describerClass, describeMethod, t));
    throw gluten::GlutenException("Error during calling Java code from native code: " + description);
  }
}

static inline jmethodID getMethodIdOrError(JNIEnv* env, jclass thisClass, const char* name, const char* sig) {
  jmethodID ret = getMethodId(env, thisClass, name, sig);
  if (ret == nullptr) {
    std::string errorMessage = "Unable to find method " + std::string(name) + " within signature" + std::string(sig);
    throw gluten::GlutenException(errorMessage);
  }
  return ret;
}

static inline jclass createGlobalClassReferenceOrError(JNIEnv* env, const char* className) {
  jclass globalClass = createGlobalClassReference(env, className);
  if (globalClass == nullptr) {
    std::string errorMessage = "Unable to CreateGlobalClassReferenceOrError for" + std::string(className);
    throw gluten::GlutenException(errorMessage);
  }
  return globalClass;
}

class SparkAllocationListener final : public gluten::AllocationListener {
 public:
  SparkAllocationListener(
      JavaVM* vm,
      jobject javaListener,
      jmethodID javaReserveMethod,
      jmethodID javaUnreserveMethod,
      int64_t blockSize)
      : vm_(vm),
        javaReserveMethod_(javaReserveMethod),
        javaUnreserveMethod_(javaUnreserveMethod),
        blockSize_(blockSize) {
    JNIEnv* env;
    attachCurrentThreadAsDaemonOrThrow(vm_, &env);
    javaListener_ = env->NewGlobalRef(javaListener);
  }

  ~SparkAllocationListener() override {
    JNIEnv* env;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), jniVersion) != JNI_OK) {
      std::cerr << "SparkAllocationListener#~SparkAllocationListener(): "
                << "JNIEnv was not attached to current thread" << std::endl;
      return;
    }
    env->DeleteGlobalRef(javaListener_);
  }

  void allocationChanged(int64_t size) override {
    updateReservation(size);
  };

 private:
  int64_t reserve(int64_t diff) {
    std::lock_guard<std::mutex> lock(mutex_);
    bytesReserved_ += diff;
    int64_t newBlockCount;
    if (bytesReserved_ == 0) {
      newBlockCount = 0;
    } else {
      // ceil to get the required block number
      newBlockCount = (bytesReserved_ - 1) / blockSize_ + 1;
    }
    int64_t bytesGranted = (newBlockCount - blocksReserved_) * blockSize_;
    blocksReserved_ = newBlockCount;
    if (bytesReserved_ > maxBytesReserved_) {
      maxBytesReserved_ = bytesReserved_;
    }
    return bytesGranted;
  }

  void updateReservation(int64_t diff) {
    JNIEnv* env;
    attachCurrentThreadAsDaemonOrThrow(vm_, &env);
    int64_t granted = reserve(diff);
    if (granted == 0) {
      return;
    }
    if (granted < 0) {
      env->CallObjectMethod(javaListener_, javaUnreserveMethod_, -granted);
      checkException(env);
      return;
    }
    env->CallObjectMethod(javaListener_, javaReserveMethod_, granted);
    checkException(env);
  }

  JavaVM* vm_;
  jobject javaListener_;
  jmethodID javaReserveMethod_;
  jmethodID javaUnreserveMethod_;
  int64_t blockSize_;
  int64_t blocksReserved_ = 0L;
  int64_t bytesReserved_ = 0L;
  int64_t maxBytesReserved_ = 0L;
  std::mutex mutex_;
};

class RssClient {
 public:
  virtual ~RssClient() = default;
};

class CelebornClient : public RssClient {
 public:
  CelebornClient(JavaVM* vm, jobject javaCelebornShuffleWriter, jmethodID javaCelebornPushPartitionDataMethod)
      : vm_(vm), javaCelebornPushPartitionData_(javaCelebornPushPartitionDataMethod) {
    JNIEnv* env;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), jniVersion) != JNI_OK) {
      throw gluten::GlutenException("JNIEnv was not attached to current thread");
    }

    javaCelebornShuffleWriter_ = env->NewGlobalRef(javaCelebornShuffleWriter);
  }

  ~CelebornClient() {
    JNIEnv* env;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), jniVersion) != JNI_OK) {
      std::cerr << "CelebornClient#~CelebornClient(): "
                << "JNIEnv was not attached to current thread" << std::endl;
      return;
    }
    env->DeleteGlobalRef(javaCelebornShuffleWriter_);
  }

  int32_t pushPartitonData(int32_t partitionId, char* bytes, int64_t size) {
    JNIEnv* env;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), jniVersion) != JNI_OK) {
      throw gluten::GlutenException("JNIEnv was not attached to current thread");
    }
    jbyteArray array = env->NewByteArray(size);
    env->SetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(bytes));
    jint celebornBytesSize =
        env->CallIntMethod(javaCelebornShuffleWriter_, javaCelebornPushPartitionData_, partitionId, array);
    checkException(env);
    return static_cast<int32_t>(celebornBytesSize);
  }

  JavaVM* vm_;
  jobject javaCelebornShuffleWriter_;
  jmethodID javaCelebornPushPartitionData_;
};
