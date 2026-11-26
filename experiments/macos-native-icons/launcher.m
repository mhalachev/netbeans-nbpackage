/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

#import <Foundation/Foundation.h>
#include <dlfcn.h>
#include <stdio.h>
typedef int (*Launch)(int,char **,int,const char **,int,const char **,const char *,const char *,const char *,const char *,unsigned char,unsigned char,unsigned char,int);
static Launch savedLaunch = NULL;
static int argumentsPrepared = 0;
int main(int argc, char **argv) {
 // libjli re-enters main on its worker thread with the full Java argv.
 if (argumentsPrepared) {
  return savedLaunch(argc,argv,0,NULL,0,NULL,"experimental","experimental","java","java",0,1,0,0);
 }
 @autoreleasepool {
  NSBundle *bundle = NSBundle.mainBundle;
  NSString *contents = [bundle.bundlePath stringByAppendingPathComponent:@"Contents"];
  NSString *profile = [NSHomeDirectory() stringByAppendingPathComponent:
    @"Library/Application Support/NBPackage Native Icon Experiment"];
  [[NSFileManager defaultManager] createDirectoryAtPath:profile
    withIntermediateDirectories:YES attributes:nil error:nil];
  NSString *log = [profile stringByAppendingPathComponent:@"launcher.log"];
  freopen(log.fileSystemRepresentation,"a",stdout); freopen(log.fileSystemRepresentation,"a",stderr);
  fprintf(stderr,"\nNative launcher bundle: %s\n",bundle.bundlePath.UTF8String);
  NSString *lib = [contents stringByAppendingPathComponent:@"Home/lib/libjli.dylib"];
  void *handle = dlopen(lib.fileSystemRepresentation,RTLD_NOW|RTLD_GLOBAL);
  if (!handle) { fprintf(stderr,"dlopen: %s\n",dlerror()); return 1; }
  Launch launch = (Launch)dlsym(handle,"JLI_Launch");
  if (!launch) { fprintf(stderr,"JLI_Launch unavailable\n"); return 1; }
  NSArray *options = [NSArray arrayWithContentsOfFile:[contents stringByAppendingPathComponent:@"Resources/native-args.plist"]];
  if (!options) { fprintf(stderr,"Missing launch arguments\n"); return 1; }
  int count = (int)options.count + argc;
  char **args = calloc(count+1,sizeof(char*));
  args[0] = argv[0];
  for (NSUInteger i=0;i<options.count;i++) {
   NSString *option = [options[i] stringByReplacingOccurrencesOfString:@"@CONTENTS@" withString:contents];
   option = [option stringByReplacingOccurrencesOfString:@"@PROFILE@" withString:profile];
   args[i+1] = strdup(option.UTF8String);
  }
  for (int i=1;i<argc;i++) args[options.count+i]=argv[i];
  savedLaunch = launch;
  argumentsPrepared = 1;
  fflush(stderr);
  return launch(count,args,0,NULL,0,NULL,"experimental","experimental","java","java",0,1,0,0);
 }
}
