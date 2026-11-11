<!-- Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com> -->

# ShareMate

[Download the latest APK](https://github.com/ShareMate-Swent/sharemate/releases/latest/download/sharemate.apk)

There can be a lot of problems when sharing a flat with roommates such as managing the purchase of consumables, planning the cleaning, organizing evenings with friends or even ensuring that the accommodation remains clean. Most of us are familiar with these problems given that the majority of students live in shared accommodation. In order to respond to all these issues, we would like to implement in particular a virtual fridge functionality (in order to share food between roommates), a chat between roommates, the possibility of reporting incidents with photos, a shared calendar, an inventory of common objects (garbage bags, toilet paper, etc.) as well as push notifications to warn roommates of an incident or a message from the chat.
## Figma 
[ShareMate-Mockups](https://www.figma.com/design/uMFTyLKhCPxhzw0zugspWH/ShareMate-%E2%80%93-Mockups)

## Architecture Diagram
Diagram is on excalidraw.com : [https://excalidraw.com/#json=k1bdsqRfMnIZkC6OTVKxL,0NhaCwdW5YgQPC8xo-5wEA](https://excalidraw.com/#json=mqEBfNNeAStqVv6rvqXjb,jKHbqIs-7lAr7gAnHZ-njw)

## User pseudos

Use `PseudoRepository.setPseudo` when creating or changing a pseudo. Pseudo claims are normalized
for case and outer whitespace, so new sign-ups cannot claim the same name with different casing.
Names already present in Firebase Auth or public profiles are not automatically reserved. The
repository updates `profiles/{uid}.displayName`, preserving the other public profile fields.
Firestore rules allow household members to read the public profile without exposing the private
user document. Profiles without a published pseudo appear as "Unknown member" in the Fridge owner
filter.

## Good Code review process :
https://github.com/swent-epfl/public/blob/main/project/README.md#reviewing-code

## How to make a good PR : 

```
#### Description
Add / edit of ...

#### Type of changes
- [ ]  Bug(s) fixes
- [ ]  New Feature
- [ ]  Refactoring / Code cleaning
- [ ]  Documentation

#### Screenshots / Demonstration (if applicable)

#### Checklist
- [ ] The code formatting is correct. (./gradlew ktfmtCheck)
- [ ] The tests pass locally.
- [ ] I verified that there are no warnings or errors in the console.
```

## Naming convention of branches : 

feature/feature-name, bugfix/issue-number, documentation/issue-number, etc...
