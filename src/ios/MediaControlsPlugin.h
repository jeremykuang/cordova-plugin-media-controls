#import <Cordova/CDVPlugin.h>

@interface MediaControlsPlugin : CDVPlugin

- (void)updateNowPlaying:(CDVInvokedUrlCommand*)command;
- (void)updatePlaybackState:(CDVInvokedUrlCommand*)command;
- (void)hide:(CDVInvokedUrlCommand*)command;
- (void)registerActionListener:(CDVInvokedUrlCommand*)command;

@end
