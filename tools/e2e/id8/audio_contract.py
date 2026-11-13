"""Consumer-visible audio checks on independently decrypted, decoded waveforms."""
import json
import time


async def quality_window(harness, session, frequencies, seconds=5, absent=()):
    """Both tones must share each playout window, not alternate or double its clock."""
    await harness.observe(.4)
    start = time.monotonic()
    await harness.observe(seconds)
    quality_span(harness, session, frequencies, start, time.monotonic(), absent)


def quality_span(harness, session, frequencies, start, end, absent=()):
    from .harness import OracleFailure
    seconds = end - start
    blocks = [block for block in harness.audio_quality
              if start <= block['t'] <= end and block['epoch'] == session.epoch]
    duration = sum(block['samples'] / block['rate'] for block in blocks)
    if not seconds - .3 <= duration <= seconds + .3:
        raise OracleFailure('audio-one-playout-clock',
                            f'{duration:.3f}s decoded audio for {seconds:.3f}s elapsed')
    valid = [block for block in blocks if 500 < block['rms'] < 16000
             and block['clipped_fraction'] < .001
             and all(block['bands'][str(hz)] > .02
                     and block['audible_10ms_fraction'][str(hz)] >= .9 for hz in frequencies)
             and all(block['bands'][str(hz)] < .01
                     and block['audible_10ms_fraction'][str(hz)] <= .1 for hz in absent)]
    ratio = len(valid) / max(1, len(blocks))
    if ratio < .94:
        raise OracleFailure('audio-continuous-composition',
                            f'{ratio:.1%} of 100ms windows contain audible {frequencies}Hz together')
    harness.checked('audio-one-playout-clock', decoded_seconds=round(duration, 3),
                    elapsed_seconds=round(end - start, 3))
    harness.checked('audio-continuous-composition', frequencies=frequencies, absent_frequencies=absent,
                    window_ms=100, valid_fraction=round(ratio, 4), minimum_fraction=.94)
    harness.continuity(harness.video_since(start, session.epoch), start, end,
                       'audio-scenario-video-continuity')


async def concurrent_audio(harness, session):
    harness.source.navigation = True
    try:
        await harness.until(lambda: any(
            block['bands']['440'] > .02 and block['bands']['880'] > .02
            for block in harness.audio_quality if block['t'] > time.monotonic() - .5),
            'concurrent-audio-composition', 8)
        await quality_window(harness, session, [440, 880])
        audio_before_bursts = audio_lifecycle(session)
        burst_start = time.monotonic()
        # Navigation STOP/INIT while music continues must not retire/reclock music.
        for _ in range(3):
            harness.source.navigation = False
            await harness.observe(.15)
            harness.source.navigation = True
            await harness.observe(.25)
        quality_span(harness, session, [440], burst_start, time.monotonic())
        require_same_audio_stream(session, audio_before_bursts)
        await quality_window(harness, session, [440, 880], 3)
    finally:
        harness.source.navigation = False
    await harness.observe(.5)
    await quality_window(harness, session, [440], 3)
    harness.checked('navigation-bursts-preserve-music')


async def track_transition(harness, session):
    epoch = session.epoch
    audio_before_tracks = audio_lifecycle(session)
    for cycle, (frequency, reinitialize) in enumerate(((660, True), (550, False), (770, True)), 1):
        harness.source.navigation = cycle != 2
        start = time.monotonic()
        await harness.source.next_track(frequency, reinitialize=reinitialize)
        await harness.until(lambda: any(
            block['t'] >= start and block['bands'][str(frequency)] > .05
            for block in harness.audio_quality), 'next-track-decoded', 5)
        recovered = time.monotonic() - start
        await quality_window(harness, session, [frequency, 880] if harness.source.navigation else [frequency])
        require_same_audio_stream(session, audio_before_tracks)
        if harness.active.epoch != epoch or session.control.closed:
            from .harness import OracleFailure
            raise OracleFailure('track-control-continuity', 'track boundary replaced the control session')
        harness.checked('next-track-decoded', cycle=cycle, frequency=frequency,
                        source_reinitialized=reinitialize, recovery_seconds=round(recovered, 3),
                        same_control_epoch=epoch)
    harness.source.navigation = False


def audio_lifecycle(session):
    from .harness import OracleFailure
    streams = frozenset(key for key, stream in session.streams.items()
                        if stream.description['type'] in (100, 102))
    if not streams:
        raise OracleFailure('audio-stream-lifetime', 'no activated receiver audio stream')
    return streams, tuple(session.audio_setups), len(session.record_requests)


def require_same_audio_stream(session, previous):
    from .harness import OracleFailure
    if audio_lifecycle(session) != previous:
        raise OracleFailure('audio-stream-lifetime', 'source transition replaced or re-RECORDed the audio stream')


async def explicit_source_intent(harness, session):
    from .source import command, stream, module_status
    from .harness import OracleFailure
    permanent_audio = audio_lifecycle(session)
    if session.resources[2].get('permanentEntity') != 1:
        raise OracleFailure('permanent-phone-audio-not-exercised', 'music did not establish permanent phone ownership')
    harness.source.navigation = True
    try:
        await harness.observe(.5)
        # Residual PCM continues on the socket, just as a producer can drain after PAUSE.
        await harness.source.write(9240, stream(0x30003, 0, b''))
        await quality_window(harness, session, [880], 3, absent=[440])
        harness.checked('explicit-pause-gates-residual-media')
        harness.source.navigation = False
        await harness.observe(.7)
        require_same_audio_stream(session, permanent_audio)
        harness.checked('navigation-noop-return-preserves-permanent-audio')
        harness.source.navigation = True
        await harness.source.write(9240, stream(0x30004, 0, b''))
        await quality_window(harness, session, [440, 880], 3)
        await harness.source.write(7240, command(65574, module_status(False)))
        await quality_window(harness, session, [880], 3, absent=[440])
        harness.checked('focus-release-gates-residual-media')
        await harness.source.write(7240, command(65574, module_status(True)))
        await harness.source.write(9240, stream(0x30004, 0, b''))
        await quality_window(harness, session, [440, 880], 3)
    finally:
        harness.source.navigation = False
    await harness.observe(.5)
    await session.change_resource(2, True)
    await harness.observe(1)
    start = time.monotonic()
    await harness.source.next_track(660, reinitialize=True)
    await harness.observe(2)
    if harness.audio_since(start, session.epoch) or session.resources[2]['entity'] != 2:
        raise OracleFailure('track-stop-stole-radio', 'STOP/INIT/continuing PCM reclaimed car-owned audio')
    resumed = time.monotonic()
    await harness.source.write(9240, stream(0x30004, 0, b''))
    await harness.until(lambda: harness.has_tone(resumed, 660, session.epoch),
                        'explicit-resume-reacquisition', 8)
    await quality_window(harness, session, [660], 3)
    harness.checked('explicit-resume-reacquisition', unchanged_focus=True)


def save_quality(harness):
    (harness.directory / 'decoded-audio-quality.json').write_text(
        json.dumps(harness.audio_quality, indent=2) + '\n')


async def initial_source_cancel(harness):
    from .source import stream
    from .harness import OracleFailure
    await harness.until(lambda: harness.active is not None and harness.active.held_audio_setup is not None,
                        'initial-audio-setup-not-exercised', 25)
    session = harness.active
    start = time.monotonic()
    await harness.source.write(9240, stream(0x30003, 0, b''))
    await harness.observe(.3)
    session.held_audio_setup.set()
    key = session.initial_audio_setup_id
    await harness.until(lambda: key in session.teardowns, 'cancelled-initial-setup-not-retired', 6)
    await harness.observe(1)
    if harness.audio_since(start, session.epoch) or any(key in request['streams'] for request in session.record_requests):
        raise OracleFailure('cancelled-initial-audio-activated', 'paused source activated an initial prepared stream')
    await harness.until(lambda: session.iap.discovery_complete, 'missing-iap-app-discovery', 16)
    harness.checked('cancelled-initial-audio-not-activated', stream=key)
    resumed = time.monotonic()
    await harness.source.write(9240, stream(0x30004, 0, b''))
    await harness.until(lambda: harness.has_tone(resumed, 440, session.epoch),
                        'resume-after-cancelled-initial-setup', 10)
    await harness.start_timing(session)
    await quality_window(harness, session, [440], 3)
    harness.checked('resume-after-cancelled-initial-setup', same_control_epoch=session.epoch)
    return session
