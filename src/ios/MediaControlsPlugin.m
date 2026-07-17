#import "MediaControlsPlugin.h"
#import <MediaPlayer/MediaPlayer.h>
#import <AVFoundation/AVFoundation.h>

@interface MediaControlsPlugin ()
@property (nonatomic, copy) NSString* actionCallbackId;
@property (nonatomic, strong) NSMutableDictionary* nowPlayingInfo;
@end

@implementation MediaControlsPlugin

- (void)pluginInitialize {
    self.nowPlayingInfo = [NSMutableDictionary dictionary];

    NSError* err = nil;
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback
                                      withOptions:0
                                            error:&err];
    [[AVAudioSession sharedInstance] setActive:YES error:&err];

    MPRemoteCommandCenter* rc = [MPRemoteCommandCenter sharedCommandCenter];

    __weak __typeof__(self) weakSelf = self;

    [rc.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        [weakSelf sendAction:@"play" value:nil];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [rc.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        [weakSelf sendAction:@"pause" value:nil];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [rc.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        [weakSelf sendAction:@"next" value:nil];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [rc.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        [weakSelf sendAction:@"previous" value:nil];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [rc.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        MPChangePlaybackPositionCommandEvent* posEvent = (MPChangePlaybackPositionCommandEvent*)event;
        [weakSelf sendAction:@"seek" value:@(posEvent.positionTime)];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    [rc.stopCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent* event) {
        [weakSelf sendAction:@"stop" value:nil];
        return MPRemoteCommandHandlerStatusSuccess;
    }];

    rc.playCommand.enabled = YES;
    rc.pauseCommand.enabled = YES;
    rc.nextTrackCommand.enabled = YES;
    rc.previousTrackCommand.enabled = YES;
    rc.changePlaybackPositionCommand.enabled = YES;
    rc.stopCommand.enabled = YES;
}

- (void)updateNowPlaying:(CDVInvokedUrlCommand*)command {
    NSDictionary* info = [command.arguments objectAtIndex:0];

    NSString* title = info[@"title"] ?: @"";
    NSString* artist = info[@"artist"] ?: @"";
    NSString* album = info[@"albumTitle"] ?: @"";
    NSNumber* duration = info[@"duration"] ?: @0;
    BOOL isPlaying = [info[@"isPlaying"] boolValue];
    NSString* artworkUrl = info[@"artworkUrl"];

    self.nowPlayingInfo[MPMediaItemPropertyTitle] = title;
    self.nowPlayingInfo[MPMediaItemPropertyArtist] = artist;
    self.nowPlayingInfo[MPMediaItemPropertyAlbumTitle] = album;
    self.nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = duration;
    self.nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @0;
    self.nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = @(isPlaying ? 1.0 : 0.0);

    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = self.nowPlayingInfo;

    __weak __typeof__(self) weakSelf = self;

    if (artworkUrl && artworkUrl.length > 0) {
        [self loadArtwork:artworkUrl completion:^(UIImage* image) {
            __typeof__(self) strongSelf = weakSelf;
            if (!strongSelf) return;
            if (image) {
                MPMediaItemArtwork* artwork = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size requestHandler:^UIImage * _Nonnull(CGSize size) {
                    return image;
                }];
                strongSelf.nowPlayingInfo[MPMediaItemPropertyArtwork] = artwork;
                [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = strongSelf.nowPlayingInfo;
            }
            CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
            [strongSelf.commandDelegate sendPluginResult:result callbackId:command.callbackId];
        }];
    } else {
        CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
    }
}

- (void)updatePlaybackState:(CDVInvokedUrlCommand*)command {
    BOOL isPlaying = [[command.arguments objectAtIndex:0] boolValue];
    NSNumber* elapsed = command.arguments.count > 1 ? [command.arguments objectAtIndex:1] : @0;

    self.nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = elapsed;
    self.nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = @(isPlaying ? 1.0 : 0.0);
    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = self.nowPlayingInfo;

    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)hide:(CDVInvokedUrlCommand*)command {
    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
    [self.nowPlayingInfo removeAllObjects];

    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)registerActionListener:(CDVInvokedUrlCommand*)command {
    self.actionCallbackId = command.callbackId;
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
    [result setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)sendAction:(NSString*)action value:(NSNumber*)value {
    if (!self.actionCallbackId) return;

    NSMutableDictionary* payload = [NSMutableDictionary dictionary];
    payload[@"action"] = action;
    if (value) {
        payload[@"value"] = value;
    }

    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:payload];
    [result setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:result callbackId:self.actionCallbackId];
}

- (void)loadArtwork:(NSString*)urlString completion:(void(^)(UIImage*))completion {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        UIImage* image = nil;
        NSURL* url = [NSURL URLWithString:urlString];
        if ([urlString hasPrefix:@"http"]) {
            NSData* data = [NSData dataWithContentsOfURL:url];
            if (data) {
                image = [UIImage imageWithData:data];
            }
        } else {
            NSString* path = [urlString hasPrefix:@"file://"] ? [url path] : urlString;
            image = [UIImage imageWithContentsOfFile:path];
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            completion(image);
        });
    });
}

@end
