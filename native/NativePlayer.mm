#import <AVFoundation/AVFoundation.h>
#import <AppKit/AppKit.h>
#import <Accelerate/Accelerate.h>
#include <jni.h>
#include <cmath>
#include <vector>
#include "io_github_junara_avpreview_NativePlayer.h"

@interface AVPSession : NSObject
@property(nonatomic,strong) AVPlayer *player;
@property(nonatomic,strong) AVPlayerItemVideoOutput *output;
/** +1 retained frame kept when the caller's buffer had the wrong size. Main-thread owned. */
@property(nonatomic,assign) CVPixelBufferRef pending;
@end
@implementation AVPSession
- (void)dealloc { if (_pending) CVPixelBufferRelease(_pending); }
@end

static NSMutableDictionary<NSNumber*,AVPSession*> *sessions;
static jlong sequence = 0;
static void onMain(dispatch_block_t block) {
    if ([NSThread isMainThread]) block();
    else dispatch_sync(dispatch_get_main_queue(), block);
}
static AVPSession *session(jlong key) { return sessions[@(key)]; }
static double seconds(CMTime t) { double s=CMTimeGetSeconds(t); return std::isfinite(s)?s:0; }

JNIEXPORT jlong JNICALL Java_io_github_junara_avpreview_NativePlayer_open(JNIEnv *env,jclass,jstring path) {
    if (!path) return 0;
    const jchar *chars=env->GetStringChars(path,nullptr);
    if (!chars) return 0;
    NSString *file=[[NSString alloc] initWithCharacters:(const unichar*)chars length:env->GetStringLength(path)];
    env->ReleaseStringChars(path,chars);
    __block jlong key=0;
    onMain(^{
        if (!sessions) sessions=[NSMutableDictionary new];
        if (![[NSFileManager defaultManager] fileExistsAtPath:file]) return;
        AVPSession *s=[AVPSession new];
        AVPlayerItem *item=[AVPlayerItem playerItemWithURL:[NSURL fileURLWithPath:file]];
        // Request BT.709 SDR output: without explicit color properties, PQ/HLG and BT.2020 code values
        // pass through untouched and HDR video looks dim and washed out on the 8-bit Swing path.
        // initWithOutputSettings: throws on unsupported keys; an uncaught exception here would take down the IDE.
        @try { s.output=[[AVPlayerItemVideoOutput alloc] initWithOutputSettings:@{
            (id)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA),
            (id)kCVPixelBufferIOSurfacePropertiesKey:@{},
            AVVideoColorPropertiesKey:@{
                AVVideoColorPrimariesKey:AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey:AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey:AVVideoYCbCrMatrix_ITU_R_709_2
            }
        }]; } @catch(NSException *e) { NSLog(@"avpreview: video output setup failed: %@",e); return; }
        [item addOutput:s.output];
        s.player=[AVPlayer playerWithPlayerItem:item];
        s.player.actionAtItemEnd=AVPlayerActionAtItemEndPause;
        key=++sequence; sessions[@(key)]=s;
    });
    return key;
}
JNIEXPORT void JNICALL Java_io_github_junara_avpreview_NativePlayer_play(JNIEnv*,jclass,jlong key,jdouble rate) {
    onMain(^{ AVPlayer *p=session(key).player; if(p) p.rate=fmax(0.25,fmin(2.0,rate)); });
}
JNIEXPORT void JNICALL Java_io_github_junara_avpreview_NativePlayer_pause(JNIEnv*,jclass,jlong key) {
    onMain(^{ [session(key).player pause]; });
}
JNIEXPORT void JNICALL Java_io_github_junara_avpreview_NativePlayer_seek(JNIEnv*,jclass,jlong key,jdouble value) {
    if(!std::isfinite(value)) return;
    onMain(^{ AVPlayer *p=session(key).player;
        double duration=seconds(p.currentItem.duration);
        double target=fmax(0,value); if(duration>0) target=fmin(duration,target);
        [p seekToTime:CMTimeMakeWithSeconds(target,600) toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
    });
}
JNIEXPORT void JNICALL Java_io_github_junara_avpreview_NativePlayer_volume(JNIEnv*,jclass,jlong key,jdouble v) {
    onMain(^{ session(key).player.volume=fmax(0,fmin(1,v)); });
}
JNIEXPORT jdoubleArray JNICALL Java_io_github_junara_avpreview_NativePlayer_state(JNIEnv *env,jclass,jlong key) {
    double storage[4]={2,0,0,0}; double *values=storage;
    onMain(^{ AVPlayer *p=session(key).player; if(!p)return;
        values[0]=p.currentItem.status==AVPlayerItemStatusFailed?2:(p.currentItem.status==AVPlayerItemStatusReadyToPlay?1:0);
        values[1]=seconds(p.currentTime); values[2]=seconds(p.currentItem.duration); values[3]=p.rate;
    });
    jdoubleArray result=env->NewDoubleArray(4); if(result)env->SetDoubleArrayRegion(result,0,4,values); return result;
}
static jstring toJava(JNIEnv *env,NSString *text) {
    NSUInteger n=text.length; std::vector<jchar> chars(n);
    [text getCharacters:(unichar*)chars.data() range:NSMakeRange(0,n)];
    return env->NewString(chars.data(),(jsize)n);
}
JNIEXPORT jstring JNICALL Java_io_github_junara_avpreview_NativePlayer_error(JNIEnv *env,jclass,jlong key) {
    __block NSString *message;
    onMain(^{ NSError *e=session(key).player.currentItem.error; message=e ? e.description : @"AVPlayer could not decode this file."; });
    return toJava(env,message);
}

static NSString *fourCC(FourCharCode c) {
    char s[5]={(char)(c>>24),(char)(c>>16),(char)(c>>8),(char)c,0};
    // Numeric subtypes (e.g. 0x6D730055) are not printable; show them as hex instead of dropping the row.
    for(int i=0;i<4;i++) if(s[i]<0x20 || s[i]>0x7E) return [NSString stringWithFormat:@"0x%08X",(unsigned)c];
    return [@(s) stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
}
static NSString *codecName(FourCharCode c) {
    static NSDictionary<NSString*,NSString*> *names=@{
        @"avc1":@"H.264",@"avc3":@"H.264",@"hvc1":@"HEVC",@"hev1":@"HEVC",@"dvh1":@"Dolby Vision (HEVC)",
        @"dvhe":@"Dolby Vision (HEVC)",@"av01":@"AV1",@"vp09":@"VP9",@"mp4v":@"MPEG-4 Visual",@"jpeg":@"Motion JPEG",
        @"apco":@"ProRes 422 Proxy",@"apcs":@"ProRes 422 LT",@"apcn":@"ProRes 422",@"apch":@"ProRes 422 HQ",
        @"ap4h":@"ProRes 4444",@"ap4x":@"ProRes 4444 XQ",@"aprn":@"ProRes RAW",@"aprh":@"ProRes RAW HQ",
        @"aac":@"AAC",@"alac":@"Apple Lossless",@"lpcm":@"Linear PCM",@"ac-3":@"AC-3",@"ec-3":@"E-AC-3",
        @"aach":@"HE-AAC",@"aacp":@"HE-AAC v2",@"aacl":@"AAC-LC",@"aace":@"AAC-ELD",
        @".mp3":@"MP3",@"opus":@"Opus",@"flac":@"FLAC",@"fLaC":@"FLAC"}; // CoreAudio uses 'flac', MP4 entries 'fLaC'

    NSString *code=fourCC(c), *name=names[code];
    return name ? [NSString stringWithFormat:@"%@ (%@)",name,code] : code;
}
static NSString *extension(CMFormatDescriptionRef d,CFStringRef key) {
    id value=(__bridge id)CMFormatDescriptionGetExtension(d,key);
    return [value isKindOfClass:NSString.class] ? value : nil;
}
static NSString *bitrate(double bps) {
    if(bps>=1e6) return [NSString stringWithFormat:@"%.2f Mbps",bps/1e6];
    if(bps>=1e3) return [NSString stringWithFormat:@"%.0f kbps",bps/1e3];
    return [NSString stringWithFormat:@"%.0f bps",bps];
}
/** Up to 3 decimals without trailing zeros: 29.97, 23.976, 30. */
static NSString *fps(float rate) {
    NSString *text=[NSString stringWithFormat:@"%.3f",rate];
    while([text hasSuffix:@"0"]) text=[text substringToIndex:text.length-1];
    if([text hasSuffix:@"."]) text=[text substringToIndex:text.length-1];
    return [text stringByAppendingString:@" fps"];
}
static NSString *timecode(double s) {
    long ms=std::lround(s*1000);
    return [NSString stringWithFormat:@"%02ld:%02ld:%02ld.%03ld",ms/3600000,ms/60000%60,ms/1000%60,ms%1000];
}
static const int64_t kInfoTimeoutSeconds=10;
/** Starts loading keys on every target at once and waits until all finish or the shared deadline passes. */
static void awaitKeys(NSArray *targets,NSArray<NSString*> *keys,dispatch_time_t deadline) {
    dispatch_group_t group=dispatch_group_create();
    for(id<AVAsynchronousKeyValueLoading> t in targets) {
        dispatch_group_enter(group);
        [t loadValuesAsynchronouslyForKeys:keys completionHandler:^{ dispatch_group_leave(group); }];
    }
    dispatch_group_wait(group,deadline); // late completions just leave a group nobody waits on
}
/**
 * Per-key load outcome. Getters may be read only after loaded() returned true; otherwise they would load
 * synchronously and block. Keys that failed or did not finish are collected for one "Metadata" row.
 */
struct KeyCheck {
    id<AVAsynchronousKeyValueLoading> target;
    NSMutableArray<NSString*> *pending=[NSMutableArray array], *failed=[NSMutableArray array];
    bool loaded(NSString *key) {
        NSError *error=nil;
        switch([target statusOfValueForKey:key error:&error]) {
            case AVKeyValueStatusLoaded: return true;
            case AVKeyValueStatusFailed:
                [failed addObject:[NSString stringWithFormat:@"%@ (%@)",key,error.localizedDescription ?: @"unknown error"]];
                return false;
            default: [pending addObject:key]; return false;
        }
    }
    /** Empty when everything loaded. */
    NSString *problems() const {
        NSMutableArray<NSString*> *parts=[NSMutableArray array];
        if(failed.count) [parts addObject:[@"Failed: " stringByAppendingString:[failed componentsJoinedByString:@", "]]];
        if(pending.count) [parts addObject:[NSString stringWithFormat:@"Timed out after %lld s: %@",
                                            kInfoTimeoutSeconds,[pending componentsJoinedByString:@", "]]];
        return [parts componentsJoinedByString:@"; "];
    }
};
/** Loads the tracks of one media type by the shared deadline; a failure or timeout is reported through check. */
static NSArray<AVAssetTrack*> *loadTracks(AVAsset *asset,AVMediaType type,NSString *label,dispatch_time_t deadline,KeyCheck &check) {
    NSMutableDictionary *box=[NSMutableDictionary dictionary]; // completion may run after a timeout: guard with @synchronized
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [asset loadTracksWithMediaType:type completionHandler:^(NSArray<AVAssetTrack*> *tracks,NSError *error) {
        @synchronized(box) { if(tracks) box[@"tracks"]=tracks; if(error) box[@"error"]=error; }
        dispatch_semaphore_signal(done);
    }];
    if(dispatch_semaphore_wait(done,deadline)!=0) { [check.pending addObject:label]; return @[]; }
    @synchronized(box) {
        if(box[@"tracks"]) return box[@"tracks"];
        NSError *error=box[@"error"];
        [check.failed addObject:[NSString stringWithFormat:@"%@ (%@)",label,error.localizedDescription ?: @"unknown error"]];
        return @[];
    }
}
static NSString *language(AVAssetTrack *t,KeyCheck &check) {
    NSString *tag=[check.target statusOfValueForKey:@"extendedLanguageTag" error:nil]==AVKeyValueStatusLoaded ? t.extendedLanguageTag : nil;
    NSString *code=tag ?: (check.loaded(@"languageCode") ? t.languageCode : nil);
    if(!code.length || [code isEqualToString:@"und"]) return nil;
    return [NSLocale.currentLocale localizedStringForLanguageCode:code] ?: code;
}
/**
 * Lines of "key\tvalue"; a line without a tab starts a section. Rows before the first section are general.
 * fileBytes (from the caller's VFS, so the size is read once) gives the overall bitrate when positive.
 * A "Metadata" row reports keys that failed or did not load before the shared deadline.
 * Blocks up to kInfoTimeoutSeconds in total: call off the main thread and off the playback worker.
 */
JNIEXPORT jstring JNICALL Java_io_github_junara_avpreview_NativePlayer_info(JNIEnv *env,jclass,jlong key,jlong fileBytes) {
    __block AVAsset *asset=nil; // strong ref: stays valid even if the session closes meanwhile
    onMain(^{ asset=session(key).player.currentItem.asset; });
    if(!asset) return nullptr;
    NSMutableString *out=[NSMutableString string];
    void (^row)(NSString*,NSString*)=^(NSString *k,NSString *v){ if(v.length) [out appendFormat:@"%@\t%@\n",k,v]; };
    @autoreleasepool {
        dispatch_time_t deadline=dispatch_time(DISPATCH_TIME_NOW,kInfoTimeoutSeconds*(int64_t)NSEC_PER_SEC);
        awaitKeys(@[asset],@[@"tracks",@"duration"],deadline);
        KeyCheck general{asset};
        if(general.loaded(@"duration")) {
            double duration=seconds(asset.duration);
            if(duration>0) {
                row(@"Duration",timecode(duration));
                if(fileBytes>0) row(@"Overall bitrate",bitrate(fileBytes*8.0/duration));
            }
        }
        if(!general.loaded(@"tracks")) { row(@"Metadata",general.problems()); return toJava(env,out); }
        // Only video and audio are shown, so timecode/text/metadata tracks are never loaded.
        NSArray<AVAssetTrack*> *videos=loadTracks(asset,AVMediaTypeVideo,@"video tracks",deadline,general);
        NSArray<AVAssetTrack*> *audios=loadTracks(asset,AVMediaTypeAudio,@"audio tracks",deadline,general);
        row(@"Metadata",general.problems());
        // Each media type loads only the keys it displays; both groups share one deadline and load concurrently.
        dispatch_group_t both=dispatch_group_create();
        dispatch_group_async(both,dispatch_get_global_queue(QOS_CLASS_UTILITY,0),^{
            awaitKeys(videos,@[@"formatDescriptions",@"naturalSize",@"preferredTransform",@"nominalFrameRate",@"estimatedDataRate"],deadline); });
        dispatch_group_async(both,dispatch_get_global_queue(QOS_CLASS_UTILITY,0),^{
            awaitKeys(audios,@[@"formatDescriptions",@"estimatedDataRate",@"languageCode",@"extendedLanguageTag"],deadline); });
        dispatch_group_wait(both,DISPATCH_TIME_FOREVER); // each awaitKeys is itself bounded by the deadline
        [videos enumerateObjectsUsingBlock:^(AVAssetTrack *t,NSUInteger i,BOOL*) {
            [out appendString:videos.count>1 ? [NSString stringWithFormat:@"Video #%lu\n",(unsigned long)i+1] : @"Video\n"];
            KeyCheck check{t};
            CMFormatDescriptionRef d=check.loaded(@"formatDescriptions") ? (__bridge CMFormatDescriptionRef)t.formatDescriptions.firstObject : nullptr;
            if(d) row(@"Codec",codecName(CMFormatDescriptionGetMediaSubType(d)));
            if(check.loaded(@"naturalSize")) {
                CGSize size=t.naturalSize;
                row(@"Resolution",[NSString stringWithFormat:@"%.0f × %.0f",size.width,size.height]);
                if(check.loaded(@"preferredTransform")) {
                    CGAffineTransform x=t.preferredTransform;
                    CGSize shown=CGRectApplyAffineTransform(CGRectMake(0,0,size.width,size.height),x).size; // standardized: non-negative
                    // Decomposed as transform = R(degrees) · F: the frame is first flipped horizontally (F, only
                    // when the determinant is negative), then rotated clockwise. R(180°)·F is a vertical flip,
                    // so that case is reported as "Vertically" with no rotation.
                    bool mirrored=x.a*x.d-x.b*x.c<0;
                    long degrees=(std::lround((mirrored?atan2(-x.b,-x.a):atan2(x.b,x.a))*180/M_PI)%360+360)%360;
                    NSString *flip=nil;
                    if(mirrored && degrees==180) { flip=@"Vertically"; degrees=0; }
                    else if(mirrored) flip=degrees ? @"Horizontally (before rotation)" : @"Horizontally";
                    if(degrees) row(@"Rotation",[NSString stringWithFormat:@"%ld° clockwise",degrees]);
                    row(@"Mirrored",flip);
                    if(degrees || mirrored) row(@"Displayed size",[NSString stringWithFormat:@"%.0f × %.0f",shown.width,shown.height]);
                }
            }
            if(check.loaded(@"nominalFrameRate") && t.nominalFrameRate>0) row(@"Frame rate",fps(t.nominalFrameRate));
            if(check.loaded(@"estimatedDataRate") && t.estimatedDataRate>0) row(@"Bitrate",bitrate(t.estimatedDataRate));
            if(d) {
                NSNumber *bits=(__bridge NSNumber*)CMFormatDescriptionGetExtension(d,kCMFormatDescriptionExtension_BitsPerComponent);
                if([bits isKindOfClass:NSNumber.class]) row(@"Bit depth",[NSString stringWithFormat:@"%@ bit",bits]);
                NSString *transfer=extension(d,kCMFormatDescriptionExtension_TransferFunction);
                row(@"Color primaries",extension(d,kCMFormatDescriptionExtension_ColorPrimaries));
                row(@"Transfer",transfer);
                row(@"Matrix",extension(d,kCMFormatDescriptionExtension_YCbCrMatrix));
                if([transfer isEqualToString:(__bridge NSString*)kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ]) row(@"HDR",@"PQ (HDR10)");
                else if([transfer isEqualToString:(__bridge NSString*)kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG]) row(@"HDR",@"HLG");
            }
            row(@"Metadata",check.problems());
        }];
        [audios enumerateObjectsUsingBlock:^(AVAssetTrack *t,NSUInteger i,BOOL*) {
            [out appendString:audios.count>1 ? [NSString stringWithFormat:@"Audio #%lu\n",(unsigned long)i+1] : @"Audio\n"];
            KeyCheck check{t};
            CMAudioFormatDescriptionRef d=check.loaded(@"formatDescriptions") ? (__bridge CMAudioFormatDescriptionRef)t.formatDescriptions.firstObject : nullptr;
            if(d) {
                row(@"Codec",codecName(CMFormatDescriptionGetMediaSubType(d)));
                const AudioStreamBasicDescription *asbd=CMAudioFormatDescriptionGetStreamBasicDescription(d);
                if(asbd) {
                    row(@"Sample rate",[NSString stringWithFormat:@"%.10g Hz",asbd->mSampleRate]); // exact: 22050, 44100
                    UInt32 ch=asbd->mChannelsPerFrame;
                    row(@"Channels",ch==1?@"1 (mono)":ch==2?@"2 (stereo)":[NSString stringWithFormat:@"%u",(unsigned)ch]);
                }
            }
            if(check.loaded(@"estimatedDataRate") && t.estimatedDataRate>0) row(@"Bitrate",bitrate(t.estimatedDataRate));
            row(@"Language",language(t,check));
            row(@"Metadata",check.problems());
        }];
    }
    return toJava(env,out);
}
JNIEXPORT jint JNICALL Java_io_github_junara_avpreview_NativePlayer_frame(JNIEnv *env,jclass,jlong key,jintArray dest) {
    __block CVPixelBufferRef pixel=nullptr;
    onMain(^{ AVPSession *s=session(key); if(!s)return;
        if(s.pending) { pixel=s.pending; s.pending=nullptr; return; }
        CMTime time=s.player.currentTime;
        if([s.output hasNewPixelBufferForItemTime:time]) pixel=[s.output copyPixelBufferForItemTime:time itemTimeForDisplay:nullptr];
    });
    if(!pixel)return 0;
    size_t w=CVPixelBufferGetWidth(pixel),h=CVPixelBufferGetHeight(pixel);
    // Bound JVM transfer cost. This scales decoded pixels, never transcodes a file.
    double scale=fmin(1.0,1280.0/fmax(w,h));
    size_t outW=fmax(1,std::lround(w*scale)),outH=fmax(1,std::lround(h*scale));
    jint dims=(jint)((outW<<16)|outH);
    if(!dest || (size_t)env->GetArrayLength(dest)!=outW*outH) {
        // Keep the frame so the caller can retry with a correctly sized buffer.
        onMain(^{ AVPSession *s=session(key);
            if(s && !s.pending) s.pending=pixel; else CVPixelBufferRelease(pixel); });
        return -dims;
    }
    CVPixelBufferLockBaseAddress(pixel,kCVPixelBufferLock_ReadOnly);
    vImage_Error err=kvImageNullPointerArgument;
    // Scale straight into the Java array (BGRA bytes == little-endian xRGB ints). No JNI calls inside.
    void *target=env->GetPrimitiveArrayCritical(dest,nullptr);
    if(target) {
        vImage_Buffer input={CVPixelBufferGetBaseAddress(pixel),h,w,CVPixelBufferGetBytesPerRow(pixel)};
        vImage_Buffer output={target,outH,outW,outW*4};
        err=vImageScale_ARGB8888(&input,&output,nullptr,kvImageNoFlags);
        env->ReleasePrimitiveArrayCritical(dest,target,0);
    }
    CVPixelBufferUnlockBaseAddress(pixel,kCVPixelBufferLock_ReadOnly); CVPixelBufferRelease(pixel);
    return err==kvImageNoError ? dims : 0;
}
JNIEXPORT void JNICALL Java_io_github_junara_avpreview_NativePlayer_close(JNIEnv*,jclass,jlong key) {
    onMain(^{ AVPSession *s=session(key); [s.player pause];
        if(s.pending) { CVPixelBufferRelease(s.pending); s.pending=nullptr; }
        [s.player.currentItem removeOutput:s.output]; [s.player replaceCurrentItemWithPlayerItem:nil];
        [sessions removeObjectForKey:@(key)];
    });
}
