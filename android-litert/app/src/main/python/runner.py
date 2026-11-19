import io, sys, threading, traceback

def run(code, timeout=20):
    """Run user code, capture print output and errors. Returns text (last 4000 chars)."""
    buf = io.StringIO()

    def target():
        old_out, old_err = sys.stdout, sys.stderr
        sys.stdout = sys.stderr = buf
        try:
            exec(compile(code, "<gupshup>", "exec"), {"__name__": "__main__"})
        except SystemExit:
            pass
        except BaseException:
            traceback.print_exc()
        finally:
            sys.stdout, sys.stderr = old_out, old_err

    t = threading.Thread(target=target, daemon=True)
    t.start()
    t.join(timeout)
    out = buf.getvalue()
    if t.is_alive():
        out += f"\n[{timeout} second se zyada lag gaye — roka gaya]"
    return out[-4000:] if out.strip() else "(code chal gaya, koi output nahi)"
