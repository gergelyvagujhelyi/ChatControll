"""Convenience script to run the server."""

import uvicorn

from app.config import DEBUG, HOST, PORT

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=HOST,
        port=PORT,
        reload=DEBUG,
        log_level="debug" if DEBUG else "info",
    )
