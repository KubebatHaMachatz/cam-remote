# Pre-Interview Assignment

## Objective

Develop an Android application that can be controlled remotely to perform the following actions:

1. Open a camera.
2. Open a camera and take a picture (rear camera only).
3. Fetch device property data (GetProp).
4. Create control application (python)

Emphasis should be placed on code planning, design, and ensuring that the application can be extended or modified easily. The application should be controlled remotely, with no need for a GUI within the Android application itself.

---

# Requirements

## 1. Open a camera

- **Requirement:** The remote client should be able to send a command to open the camera on the Android device.
- **Functionality:**
  - Open the camera app.
  - Handle permissions for camera access securely.
  - Return operation status to the remote control application.

## 2. Open a camera and take a picture (Rear camera only)

- **Requirement:** The remote client should be able to send a command to open the rear camera and take a picture.
- **Functionality:**
  - Open the rear camera.
  - Handle permissions for camera access and storage access securely.
  - Capture an image and save it to a specified location.
  - Return the fetched property to the remote client.
 

## 3. Fetch device property data (GetProp)

- **Requirement:** The remote client should be able to send a command to fetch and display a specific device property.
- **Functionality:**
  - Fetch a device property as using `getprop`.
  - Return the fetched property to the remote client.

## 4. Create control application (python)

- **Requirement:** The control application should be able to send commands to the remote application.
- **Functionality:**
  - Send command to device:
    - Open a camera
    - Open a camera and take picture
    - getprop.
  - Return the status of the operation and relevant output (if needed).

---

# Additional requirements

## 1. Code Planning and Design

- The code should be modular and well-organized.
- Use best practices for coding standards.
- Include comments and docstrings to explain the purpose of each function.

## 2. Extensibility

- The codebase should be designed in such a way that new functionalities can be easily added.
- Ensure that the code can be modified with minimal changes to existing functionality.

---

# Submission Instructions

1. Submit your complete project along with an explanation of the design decisions made.
2. Include a README file explaining how to run the project and any prerequisites.
3. Provide a brief documentation explaining the structure of your code and how to extend it.

Please ensure you follow the best practices and coding standards while completing this assignment. Good luck!
