# Repository instructions

## Prompt commands

If user's prompt is only `Cpd`, this means to "commit, push, deploy":

- git commit message must be descriptive, focusing on what's fixed and how it was fixed
- git push
- deploy via running ./install_service.sh script

## History-query semantics

- Do not add `hours > 0` or `startTime > 0` guards to RoomHistoryManager history queries.
