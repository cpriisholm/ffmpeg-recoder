# ffmpeg-recoder
A script that eases the use of  [**ffmpeg**](http://www.ffmpeg.org) for certain media file recoding operations. 

**Ffmpeg** is an fantastic tool with nearly unlimited capabilities when it comes to handling media files - but that in turn leads to an quite complex command line interface.

For my particular use case I need to convert DVB-T recordings (saved as M2T files) to MKV container files while also applying better compression than provided by DVB-T. I also need a way to trim the length of the recordings, and on occasion also to concatenate clip files from my camera into one file.

This little script enables me to do that with a simpler command line syntax than required by **ffmpeg**.

If you want to see the actuall **ffmpeg** command call this script with the `--dryrun` option with prints relevant information such as the **ffmpeg** command.

Run the script without parameters to get help, or with the `--help` option. 

You will need a newer Groovy installation (2.x) to run the script. If not already available on your machine I suggest checking out the amazing [**SDKMAN**](http://sdkman.io/) - it just makes anything **JVM** related so much easier to install... 

### Concatenate files
`./Recoder.groovy -c -o combined clip1.MTS clip2.MTS`

Will generate a **combined.MTS** file from the two parts. 
Under the hood it will create a temporary file listing the input files and invokes **ffmpeg** with that file (use option `--dryrun` to see the command):

`ffmpeg -f concat -safe 0 -i /tmp/recoder-concat-3987336201045907964.txt -c copy "combined.MTS"`

### Stream info
`./Recoder.groovy -z part2.MTS`

This print all the streams found in the input file as well as the suggested mapping.
It uses **ffprobe** to get all the streams, 
and then uses the `parseStreams()` method to determine what to suggest:

```
Stream #0:0[0x1011]: Video: h264 (High) (HDMV / 0x564D4448), yuv420p(top first), 1920x1080 [SAR 1:1 DAR 16:9], 25 fps, 25 tbr, 90k tbn, 50 tbc
Stream #0:1[0x1100]: Audio: ac3 (AC-3 / 0x332D4341), 48000 Hz, stereo, fltp, 256 kb/s
Stream #0:2[0x1200]: Subtitle: hdmv_pgs_subtitle ([144][0][0][0] / 0x0090), 1920x1080
Suggested mapping:
video...: 0 
audio...: 1 -- ac3 (AC-3 / 0x332D4341), 48000 Hz, stereo, fltp, 256 kb/s
```
The suggested mapping list the streans that will be the ones used per default when `-z`/`--streams` option is used along with other parameters (see below).
Hence you may want to look at the `parseStreams()` method to adapt it to your specific use case (e.g. if you preferred subtitle 
language is not Danish and so forth).

### Trim clip length
`./Recoder.groovy --begin 00:01:00 --end 00:05:00 -o trimmed.MTS clip.MTS`

**Ffmpeg** wants the start time and the period, I find it easier to give it the start time and the end time,
as can be seen from the resulting **ffmpeg** command it does just that:

`ffmpeg -y -ss 00:01:00 -i "clip.MTS" -t 00:04:00 "trimmed.MTS"`

### Transcode

The script assumes that either the streams are copied from the input and to the output container, except
in case the `--transcode` / `-x` option is given. If needed it will transcode the input video stream to H264 in the output.

`./Recoder.groovy -z -x -o.mkv clip.MTS`

Will convert the clip into a MKV file named **clip.mkv** and transcodes the video stream to H264. 
The `-z` option causes it to automatically suggest a mapping of the streams (as describe above).
The resulting **ffmpeg** command looks like this:

`ffmpeg -y -i "clip.MTS" -map 0:0 -vcodec h264 -map 0:1 -acodec copy "clip.mkv"`

Transcoding and trimming can be combined:

`./Recoder.groovy -b 00:01:00 -e 00:05:00 -z -x -o trimmed.mkv clip.MTS`

It is possible to adjust the mapping of the streams but of course it is much easier if the `parseStreams()` method can
handle the mapping. It then becomes easy to transcode a batch of files unattended:

`./Recoder.groovy -z -x -o .mkv *.MTS`

The command transcodes all MTS files to MKV files using H264 encoding.

Now you start seeing how much simpler this command is compared to what actually is needed by **ffmpeg** - especially when the input files may contain different streams.

The script invokes **ffmpeg** one file at the time, and reports progress in percent to the terminal:

```
= = = = =
clip1.MTS
= = = = =
Run time: 00:00:11
100,0% >> frame=  299 fps=3.2 q=-1.0 Lsize=    5127kB time=00:00:11.93 bitrate=3519.0kbits/s speed=0.127x    

= = = = =
clip2.MTS
= = = = =
Run time: 00:00:11
100,0% >> frame=  299 fps=3.5 q=-1.0 Lsize=    5127kB time=00:00:11.93 bitrate=3519.0kbits/s speed=0.14x
```
As result the files **clip1.mkv** and **clip2.mkv** are generated.

*The speed of course depends on your hardware - you cannot beat the keyboard on this old Thinkpad, but the performance... :-)*

##RecodeSpec.groovy

This is a [**spock**](http://sprockframework.org) test specification, which in turn is based on JUnit so you can simply run the class with Groovy:

`groovy RecodeSpec.groovy`
