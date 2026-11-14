// Isolates AVFoundation decoding from JNI and IntelliJ. No window or audio output.
#import <AVFoundation/AVFoundation.h>
#import <Foundation/Foundation.h>
int main(int argc,const char **argv) {
    @autoreleasepool {
        if(argc!=2)return 2;
        AVPlayerItem *item=[AVPlayerItem playerItemWithURL:[NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[1]]]];
        AVPlayerItemVideoOutput *output=[[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:@{(id)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA)}];
        [item addOutput:output];
        AVPlayer *player=[AVPlayer playerWithPlayerItem:item]; player.muted=YES;
        NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:10];
        while(item.status==AVPlayerItemStatusUnknown && deadline.timeIntervalSinceNow>0)
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.03]];
        if(item.status!=AVPlayerItemStatusReadyToPlay) {
            fprintf(stderr,"AVFoundation-only probe: %s\n",item.error.description.UTF8String); return 1;
        }
        [player play];
        int frames=0;
        while(deadline.timeIntervalSinceNow>0 && frames<5 && item.status!=AVPlayerItemStatusFailed) {
            [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.03]];
            CVPixelBufferRef pixel=[output copyPixelBufferForItemTime:player.currentTime itemTimeForDisplay:nil];
            if(pixel) { frames++; CVPixelBufferRelease(pixel); }
        }
        [player pause];
        printf("AVFoundation-only frames=%d error=%s\n",frames,item.error.description.UTF8String);
        return frames>=5?0:1;
    }
}
