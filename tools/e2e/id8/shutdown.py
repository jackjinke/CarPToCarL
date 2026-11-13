"""Bounded network shutdown that continues cleanup and surfaces every failure."""
import asyncio


class Shutdown:
    def __init__(self):
        self.errors = []

    async def wait(self, label, awaitable, timeout=3):
        try:
            await asyncio.wait_for(awaitable, timeout)
        except Exception as error:
            self.errors.append(f'{label}: {type(error).__name__}: {error}')

    async def cancel(self, tasks):
        tasks = {task for task in tasks if task is not asyncio.current_task()}
        for task in tasks:
            task.cancel()
        if not tasks:
            return
        done, pending = await asyncio.wait(tasks, timeout=3)
        for task in done:
            if not task.cancelled():
                error = task.exception()
                if error:
                    self.errors.append(f'task shutdown: {type(error).__name__}: {error}')
        if pending:
            self.errors.append(f'task shutdown: {len(pending)} tasks did not stop within3s')
            for task in pending:
                task.cancel()

    def finish(self):
        if self.errors:
            raise RuntimeError('; '.join(self.errors))
