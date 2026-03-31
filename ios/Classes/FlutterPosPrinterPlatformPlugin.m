#import "FlutterPosPrinterPlatformPlugin.h"
#import "ConnecterManager.h"

@interface FlutterPosPrinterPlatformPlugin ()
@property(nonatomic, retain) NSObject<FlutterPluginRegistrar> *registrar;
@property(nonatomic, retain) FlutterMethodChannel *channel;
@property(nonatomic, retain) BluetoothPrintStreamHandler *stateStreamHandler;
@property(nonatomic) NSMutableDictionary *scannedPeripherals;
@end

@implementation FlutterPosPrinterPlatformPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  FlutterMethodChannel* channel = [FlutterMethodChannel
      methodChannelWithName:NAMESPACE @"/methods"
            binaryMessenger:[registrar messenger]];
  FlutterEventChannel* stateChannel = [FlutterEventChannel eventChannelWithName:NAMESPACE @"/state" binaryMessenger:[registrar messenger]];
  FlutterPosPrinterPlatformPlugin* instance = [[FlutterPosPrinterPlatformPlugin alloc] init];

  instance.channel = channel;
  instance.scannedPeripherals = [NSMutableDictionary new];
    
  // STATE
  BluetoothPrintStreamHandler* stateStreamHandler = [[BluetoothPrintStreamHandler alloc] init];
  [stateChannel setStreamHandler:stateStreamHandler];
  instance.stateStreamHandler = stateStreamHandler;

  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall*)call result:(FlutterResult)result {
  NSLog(@"call method -> %@", call.method);
    
  if ([@"state" isEqualToString:call.method]) {
    result(nil);
  } else if([@"isAvailable" isEqualToString:call.method]) {
    
    result(@(YES));
  } else if([@"isConnected" isEqualToString:call.method]) {
    
    result(@(NO));
  } else if([@"isOn" isEqualToString:call.method]) {
    result(@(YES));
  }else if([@"startScan" isEqualToString:call.method]) {
      NSLog(@"getDevices method -> %@", call.method);
      [self.scannedPeripherals removeAllObjects];
      
      if (Manager.bleConnecter == nil) {
          [Manager didUpdateState:^(NSInteger state) {
              switch (state) {
                  case CBCentralManagerStateUnsupported:
                      NSLog(@"The platform/hardware doesn't support Bluetooth Low Energy.");
                      break;
                  case CBCentralManagerStateUnauthorized:
                      NSLog(@"The app is not authorized to use Bluetooth Low Energy.");
                      break;
                  case CBCentralManagerStatePoweredOff:
                      NSLog(@"Bluetooth is currently powered off.");
                      break;
                  case CBCentralManagerStatePoweredOn:
                      [self startScan];
                      NSLog(@"Bluetooth power on");
                      break;
                  case CBCentralManagerStateUnknown:
                  default:
                      break;
              }
          }];
      } else {
          [self startScan];
      }
      
    result(nil);
  } else if([@"stopScan" isEqualToString:call.method]) {
    [Manager stopScan];
    result(nil);
  } else if([@"connect" isEqualToString:call.method]) {
    NSDictionary *device = [call arguments];
    @try {
      NSLog(@"connect device begin -> %@", [device objectForKey:@"name"]);
      NSString *address = [device objectForKey:@"address"];

      if (address == nil || address.length == 0) {
        NSLog(@"[FlutterPosPrinterPlatformPlugin] No address provided");
        result([FlutterError errorWithCode:@"INVALID_ADDRESS"
                                   message:@"No device address provided"
                                   details:nil]);
        return;
      }

      // Ensure BLE is initialized
      if (Manager.bleConnecter == nil) {
        NSLog(@"[FlutterPosPrinterPlatformPlugin] bleConnecter is nil, initializing BLE and scanning...");
        __weak typeof(self) weakSelf = self;
        __block BOOL resultSent = NO;

        [Manager didUpdateState:^(NSInteger state) {
          if (state == CBCentralManagerStatePoweredOn && !resultSent) {
            __strong typeof(weakSelf) strongSelf = weakSelf;
            if (strongSelf) {
              // BLE is ready — scan to find the device
              [strongSelf scanAndConnect:address result:result resultSent:&resultSent];
            }
          } else if ((state == CBCentralManagerStatePoweredOff ||
                      state == CBCentralManagerStateUnsupported ||
                      state == CBCentralManagerStateUnauthorized) && !resultSent) {
            resultSent = YES;
            result(@(NO));
          }
        }];
        return;
      }

      // BLE is initialized — check if peripheral is in cache
      CBPeripheral *peripheral = [_scannedPeripherals objectForKey:address];

      if (peripheral != nil) {
        // Peripheral found in cache — connect directly
        [self connectToPeripheral:peripheral result:result];
      } else {
        // Peripheral not in cache — do a quick scan to find it
        NSLog(@"[FlutterPosPrinterPlatformPlugin] Peripheral not in cache, scanning for address: %@", address);
        __block BOOL resultSent = NO;
        [self scanAndConnect:address result:result resultSent:&resultSent];
      }

    } @catch(NSException *e) {
      NSLog(@"[FlutterPosPrinterPlatformPlugin] connect exception: %@", e);
      result([FlutterError errorWithCode:@"CONNECT_ERROR"
                                 message:e.reason ?: @"Unknown connection error"
                                 details:nil]);
    }
  } else if([@"disconnect" isEqualToString:call.method]) {
    @try {
      [Manager close];
      result(nil);
    } @catch(NSException *e) {
      NSLog(@"[FlutterPosPrinterPlatformPlugin] disconnect exception: %@", e);
      result([FlutterError errorWithCode:@"DISCONNECT_ERROR"
                                 message:e.reason ?: @"Unknown disconnect error"
                                 details:nil]);
    }
  } else if([@"writeData" isEqualToString:call.method]) {
       @try {
           NSDictionary *args = [call arguments];
           
           if (Manager.connecter == nil) {
               result([FlutterError errorWithCode:@"NOT_CONNECTED"
                                          message:@"Printer is not connected"
                                          details:nil]);
               return;
           }

           NSMutableArray *bytes = [args objectForKey:@"bytes"];

           NSNumber* lenBuf = [args objectForKey:@"length"];
           int len = [lenBuf intValue];

           if (len <= 0 || bytes == nil) {
               result([FlutterError errorWithCode:@"INVALID_DATA"
                                          message:@"Invalid data to write"
                                          details:nil]);
               return;
           }

           char cArray[len];

           for (int i = 0; i < len; ++i) {
               cArray[i] = [bytes[i] charValue];
           }
           NSData *data2 = [NSData dataWithBytes:cArray length:sizeof(cArray)];

           [Manager write:data2 progress:nil receCallBack:nil];
           result(nil);
       } @catch(NSException *e) {
           NSLog(@"[FlutterPosPrinterPlatformPlugin] writeData exception: %@", e);
           result([FlutterError errorWithCode:@"WRITE_ERROR"
                                      message:e.reason ?: @"Unknown write error"
                                      details:nil]);
       }
  }
}

-(void)startScan {
    [Manager scanForPeripheralsWithServices:nil options:nil discover:^(CBPeripheral * _Nullable peripheral, NSDictionary<NSString *,id> * _Nullable advertisementData, NSNumber * _Nullable RSSI) {
        if (peripheral != nil && peripheral.name != nil && peripheral.name.length > 0) {

            NSLog(@"find device -> %@", peripheral.name);
            [self.scannedPeripherals setObject:peripheral forKey:[[peripheral identifier] UUIDString]];
            
            NSDictionary *device = [NSDictionary dictionaryWithObjectsAndKeys:peripheral.identifier.UUIDString,@"address",peripheral.name,@"name",nil,@"type",nil];

            dispatch_async(dispatch_get_main_queue(), ^{
                [self->_channel invokeMethod:@"ScanResult" arguments:device];
            });
        }
    }];
    
}

/**
 * Quick scan to find a specific peripheral by UUID, then connect to it.
 * Times out after 5 seconds if the device is not found.
 */
-(void)scanAndConnect:(NSString *)address result:(FlutterResult)result resultSent:(BOOL *)resultSent {
    NSLog(@"[FlutterPosPrinterPlatformPlugin] scanAndConnect started for address: %@", address);

    // Set a timeout for the scan
    __block BOOL found = NO;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (!found && !(*resultSent)) {
            *resultSent = YES;
            [Manager stopScan];
            NSLog(@"[FlutterPosPrinterPlatformPlugin] scanAndConnect timed out for address: %@", address);
            result(@(NO));
        }
    });

    // Start scanning
    [Manager scanForPeripheralsWithServices:nil options:nil discover:^(CBPeripheral * _Nullable peripheral, NSDictionary<NSString *,id> * _Nullable advertisementData, NSNumber * _Nullable RSSI) {
        if (peripheral != nil && peripheral.name != nil && peripheral.name.length > 0) {
            // Cache every discovered peripheral
            [self.scannedPeripherals setObject:peripheral forKey:[[peripheral identifier] UUIDString]];

            // Check if this is the device we're looking for
            if ([[[peripheral identifier] UUIDString] isEqualToString:address]) {
                found = YES;
                [Manager stopScan];
                NSLog(@"[FlutterPosPrinterPlatformPlugin] scanAndConnect found device: %@", peripheral.name);

                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!(*resultSent)) {
                        [self connectToPeripheral:peripheral result:result];
                    }
                });
            }
        }
    }];
}

/**
 * Connect to a specific peripheral and return the result to Dart.
 */
-(void)connectToPeripheral:(CBPeripheral *)peripheral result:(FlutterResult)result {
    __block BOOL resultSent = NO;
    __weak typeof(self) weakSelf = self;
    self.state = ^(ConnectState state) {
        __strong typeof(weakSelf) strongSelf = weakSelf;
        if (strongSelf) {
            [strongSelf updateConnectState:state];
        }
        // Return the connection result to Dart
        if (!resultSent) {
            if (state == CONNECT_STATE_CONNECTED) {
                resultSent = YES;
                result(@(YES));
            } else if (state == CONNECT_STATE_FAILT || state == CONNECT_STATE_TIMEOUT || state == CONNECT_STATE_DISCONNECT) {
                resultSent = YES;
                result(@(NO));
            }
        }
    };
    [Manager connectPeripheral:peripheral options:nil timeout:5 connectBlack:self.state];
}

-(void)updateConnectState:(ConnectState)state {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSNumber *ret = @0;
        switch (state) {
            case CONNECT_STATE_CONNECTING:
                NSLog(@"status -> %@", @"Connecting ...");
                ret = @1;
                break;
            case CONNECT_STATE_CONNECTED:
                NSLog(@"status -> %@", @"Connection success");
                ret = @2;
                break;
            case CONNECT_STATE_FAILT:
                NSLog(@"status -> %@", @"Connection failed");
                ret = @0;
                break;
            case CONNECT_STATE_DISCONNECT:
                NSLog(@"status -> %@", @"Disconnected");
                ret = @0;
                break;
            default:
                NSLog(@"status -> %@", @"Connection timed out");
                ret = @0;
                break;
        }
        
         NSDictionary *dict = [NSDictionary dictionaryWithObjectsAndKeys:ret,@"id",nil];
        if(_stateStreamHandler.sink != nil) {
          self.stateStreamHandler.sink([dict objectForKey:@"id"]);
        }
    });
}

@end

@implementation BluetoothPrintStreamHandler

- (FlutterError*)onListenWithArguments:(id)arguments eventSink:(FlutterEventSink)eventSink {
  self.sink = eventSink;
  return nil;
}

- (FlutterError*)onCancelWithArguments:(id)arguments {
  self.sink = nil;
  return nil;
}

@end
