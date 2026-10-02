"""Both release paths share one version/source/byte identity, including the retired full Render builder."""

def require_current_main(candidate_source, remote_listing):
    refs = [line.split() for line in remote_listing.splitlines() if line.strip()]
    if refs != [[candidate_source, 'refs/heads/main']]:
        raise ValueError('Retired full builder requires a fresh checkout of current GitHub main')

def check_channel(candidate_code, candidate_source, candidate_sha256, published, channel):
    old_code = published.get('versionCode')
    if type(old_code) is not int or old_code < 1:
        raise ValueError(f'{channel} version is missing or invalid')
    if candidate_code < old_code:
        raise ValueError(f'Refusing version {candidate_code}: {channel} already serves {old_code}')
    if candidate_code == old_code and (
            published.get('sourceCommit') != candidate_source
            or published.get('sha256') != candidate_sha256):
        raise ValueError(f'Refusing changed source or APK bytes for version {candidate_code} on {channel}')
