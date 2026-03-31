//
//  ConnecterManager.m
//  GSDK
//
//

#import "ConnecterManager.h"
#import <objc/runtime.h>

#pragma mark - Safe cancelPeripheralConnection: swizzle

@implementation CBCentralManager (SafeCancel)

+ (void)load {
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class class = [self class];

        // Swizzle cancelPeripheralConnection:
        {
            SEL originalSelector = @selector(cancelPeripheralConnection:);
            SEL swizzledSelector = @selector(safe_cancelPeripheralConnection:);

            Method originalMethod = class_getInstanceMethod(class, originalSelector);
            Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);

            BOOL didAdd = class_addMethod(class,
                                           originalSelector,
                                           method_getImplementation(swizzledMethod),
                                           method_getTypeEncoding(swizzledMethod));
            if (didAdd) {
                class_replaceMethod(class,
                                    swizzledSelector,
                                    method_getImplementation(originalMethod),
                                    method_getTypeEncoding(originalMethod));
            } else {
                method_exchangeImplementations(originalMethod, swizzledMethod);
            }
        }

        // Swizzle connectPeripheral:options:
        {
            SEL originalSelector = @selector(connectPeripheral:options:);
            SEL swizzledSelector = @selector(safe_connectPeripheral:options:);

            Method originalMethod = class_getInstanceMethod(class, originalSelector);
            Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);

            BOOL didAdd = class_addMethod(class,
                                           originalSelector,
                                           method_getImplementation(swizzledMethod),
                                           method_getTypeEncoding(swizzledMethod));
            if (didAdd) {
                class_replaceMethod(class,
                                    swizzledSelector,
                                    method_getImplementation(originalMethod),
                                    method_getTypeEncoding(originalMethod));
            } else {
                method_exchangeImplementations(originalMethod, swizzledMethod);
            }
        }
    });
}

- (void)safe_cancelPeripheralConnection:(CBPeripheral *)peripheral {
    if (peripheral == nil) {
        NSLog(@"[CBCentralManager+SafeCancel] Blocked cancelPeripheralConnection: with nil peripheral");
        return;
    }
    [self safe_cancelPeripheralConnection:peripheral];
}

- (void)safe_connectPeripheral:(CBPeripheral *)peripheral options:(NSDictionary<NSString *,id> *)options {
    if (peripheral == nil) {
        NSLog(@"[CBCentralManager+SafeCancel] Blocked connectPeripheral:options: with nil peripheral");
        return;
    }
    [self safe_connectPeripheral:peripheral options:options];
}

@end

#pragma mark - ConnecterManager

@interface ConnecterManager(){
    ConnectMethod currentConnMethod;
}
@end

@implementation ConnecterManager

static ConnecterManager *manager;
static dispatch_once_t once;

+(instancetype)sharedInstance {
    dispatch_once(&once, ^{
        manager = [[ConnecterManager alloc]init];
    });
    return manager;
}

-(void)scanForPeripheralsWithServices:(nullable NSArray<CBUUID *> *)serviceUUIDs options:(nullable NSDictionary<NSString *, id> *)options discover:(void(^_Nullable)(CBPeripheral *_Nullable peripheral,NSDictionary<NSString *, id> *_Nullable advertisementData,NSNumber *_Nullable RSSI))discover{
    if (_bleConnecter == nil) {
        NSLog(@"[ConnecterManager] scanForPeripherals called but bleConnecter is nil");
        return;
    }
    [_bleConnecter scanForPeripheralsWithServices:serviceUUIDs options:options discover:discover];
}

-(void)didUpdateState:(void(^)(NSInteger state))state {
    if (_bleConnecter == nil) {
        currentConnMethod = BLUETOOTH;
        [self initConnecter:currentConnMethod];
    }
    [_bleConnecter didUpdateState:state];
}

-(void)initConnecter:(ConnectMethod)connectMethod {
    switch (connectMethod) {
        case BLUETOOTH:
            _bleConnecter = [BLEConnecter new];
            _connecter = _bleConnecter;
            break;
        default:
            break;
    }
}

-(void)stopScan {
    if (_bleConnecter == nil) return;
    [_bleConnecter stopScan];
}

-(void)connectPeripheral:(CBPeripheral *)peripheral options:(nullable NSDictionary<NSString *,id> *)options timeout:(NSUInteger)timeout connectBlack:(void(^_Nullable)(ConnectState state)) connectState{
    if (_bleConnecter == nil) {
        NSLog(@"[ConnecterManager] connectPeripheral called but bleConnecter is nil");
        if (connectState) connectState(CONNECT_STATE_FAILT);
        return;
    }
    if (peripheral == nil) {
        NSLog(@"[ConnecterManager] connectPeripheral called with nil peripheral");
        if (connectState) connectState(CONNECT_STATE_FAILT);
        return;
    }

    // Simply re-assign _connecter and let the GSDK handle reconnection internally.
    // The GSDK's connectPeripheral: will disconnect any previous connection if needed.
    // We never recreate BLEConnecter — a single instance with a single CBCentralManager
    // is used for the entire app lifetime to avoid orphaned managers blocking BLE scan.
    _connecter = _bleConnecter;
    [_bleConnecter connectPeripheral:peripheral options:options timeout:timeout connectBlack:connectState];
}

-(void)connectPeripheral:(CBPeripheral * _Nullable)peripheral options:(nullable NSDictionary<NSString *,id> *)options {
    if (_bleConnecter == nil || peripheral == nil) {
        NSLog(@"[ConnecterManager] connectPeripheral called with nil bleConnecter or peripheral");
        return;
    }
    [_bleConnecter connectPeripheral:peripheral options:options];
}

-(void)write:(NSData *_Nullable)data progress:(void(^_Nullable)(NSUInteger total,NSUInteger progress))progress receCallBack:(void (^_Nullable)(NSData *_Nullable))callBack {
    if (_bleConnecter == nil || data == nil) {
        NSLog(@"[ConnecterManager] write:progress:receCallBack: called with nil bleConnecter or data");
        return;
    }
    [_bleConnecter write:data progress:progress receCallBack:callBack];
}

-(void)write:(NSData *)data receCallBack:(void (^)(NSData *))callBack {
#ifdef DEBUG
    NSLog(@"[ConnecterManager] write:receCallBack:");
#endif
    if (_connecter == nil || data == nil) {
        NSLog(@"[ConnecterManager] write:receCallBack: called with nil connecter or data");
        return;
    }
    _bleConnecter.writeProgress = nil;
    [_connecter write:data receCallBack:callBack];
}

-(void)write:(NSData *)data {
#ifdef DEBUG
    NSLog(@"[ConnecterManager] write:");
#endif
    if (_connecter == nil || data == nil) {
        NSLog(@"[ConnecterManager] write: called with nil connecter or data");
        return;
    }
    _bleConnecter.writeProgress = nil;
    [_connecter write:data];
}

-(void)close {
    // Disconnect so other devices can connect to the printer.
    // cancelPeripheralConnection: is swizzled to be nil-safe, so closePeripheral:
    // will no longer crash even if the GSDK internally passes nil.
    if (_bleConnecter != nil) {
        CBPeripheral *peripheral = _bleConnecter.connPeripheral;
        if (peripheral != nil) {
            NSLog(@"[ConnecterManager] Disconnecting peripheral: %@", peripheral.name);
            [_bleConnecter closePeripheral:peripheral];
        }
        _bleConnecter.connPeripheral = nil;
    }
    _connecter = nil;
}

@end
