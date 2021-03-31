# Artifact for Lightweight Robust Size Aware Cache Management paper

This repository branch includes all the necessary code and instructions to reproduce the main results presented in the [paper](https://arxiv.org/abs/2105.08770).

## Caffeine Simulator

To run LRU, GDSF and W-TinyLFU versions policies, we use [Caffeine Simulator](https://github.com/ben-manes/caffeine/wiki/Simulator).

### Initial setup

    # clone the repository:
    git clone https://github.com/ohadeytan/caffeine.git

    # move to the right branch:
    git checkout VLDB_submission

    # allow gradle download dependencies and self check its running:
    ./gradlew build -x test

    # now you should be able to run:
    ./gradlew simulator:run
This will run the `AV-SLRU` policy on a small portion of `CDN1` trace.

### Configurations

In order to run each policy, use the following configurations above the default configuration of the simulator. That can be done by editing the [`application.conf`](https://github.com/ohadeytan/caffeine/blob/VLDB_submission/simulator/src/main/resources/application.conf) file.

Policy | Changes 
-|-
LRU |<pre>policies = [ linked.Lru ]</pre>
GDSF |<pre>policies = [ others.GDSF ]</pre>
IV-SLRU |<pre>policies = [ sketch.SizedWindowTinyLfu ]</pre>
IV-Sampled |<pre>policies = [ sketch.SizedSampledTinyLfu ]</pre>
QV-SLRU |<pre>policies = [ sketch.RistrettoSizedWindowTinyLfu ]</pre>
QV-Sampled |<pre>policies = [ sketch.RistrettoSizedSampledTinyLfu ]</pre>
AV-SLRU |<pre>policies = [ sketch.SumSizedWindowTinyLfu ]</pre>
AV-Sampled |<pre>policies = [ sketch.SumSizedSampledTinyLfu ]</pre>

For the sampling methods use the following configs:
Method | Changes
-|-
Random |<pre>sized-window-tiny-lfu { sample = Rand }</pre>
Frequency |<pre>sized-window-tiny-lfu { sample = Freq }</pre>
Frequency/Size |<pre>sized-window-tiny-lfu { sample = FreqToSize }</pre>
Size |<pre>sized-window-tiny-lfu { sample = Size }</pre>
Needed Size |<pre>sized-window-tiny-lfu { sample = SizeDist }</pre>

The size of the cache can be set via the `maximum-size` parameter.

The path to and the format of the trace through the `files` section.

## Other simulators

To run AdaptSize, LHD and LRB we use their authors implementations
Policy | Source 
-|-
AdaptSize | https://github.com/dasebe/webcachesim
LHD | https://github.com/CMU-CORGI/LHD
LRB | https://github.com/sunnyszy/lrb

## Traces

All traces can be downloaded freely and run in the simulator with the appropriate format.

Trace | Link | Notes
-|-|-
CDN1 | [Practical Bounds Github](https://github.com/dasebe/optimalwebcaching) | [Direct link](http://dat-berger.de/cachetraces/sigmetrics18/cdn1_500m_sigmetrics18.tr.lzma)
CDN2 | [LRB Github](https://github.com/sunnyszy/lrb) | [Direct link](http://lrb.cs.princeton.edu/wiki2018.tr.tar.gz)
CDN3 | [LRB Github](https://github.com/sunnyszy/lrb) | [Direct link](http://lrb.cs.princeton.edu/wiki2019.tr.tar.gz)
MSR1 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/388) | `proj2` file
MSR2 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/388) | `src10` file
MSR3 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/388) | `usr0` file
SYSTOR1 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/4964) | Combination of the files in `systor17-01.tar`
SYSTOR2 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/4964) | Combination of the files in `systor17-02.tar`
SYSTOR3 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/4964) | Combination of the files in `systor17-03.tar`
TENCENT1 | [SNIA IOTTA Repository](http://iotta.snia.org/traces/27917) | Combination of the files from `02-02-2016`

