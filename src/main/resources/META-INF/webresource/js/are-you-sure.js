/*
 * Copyright (C) 2011 Everit Kft. (http://www.everit.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
function AreYouSureDialog() {
  var _dialog = null;
  var _yesHandler = null;
  
  $(function() {
    _dialog = ajsDialogFromTemplate("#areYouSureDialog", {
      width : 550,
      height : 180,
      id : "are-you-sure-dialog",
      closeOnOutsideClick : true
    });
  });
  
  
  this.show = function(yesHandler, event) {
    if (event) {
      event.preventDefault();
    }
    _yesHandler = yesHandler;
    _dialog.show();
  }
  
  this._handleYes = function(event) {
    event.preventDefault();
    if (_yesHandler) {
      _yesHandler();
    }
    _dialog.hide();
  }
  
  this._handleNo = function(event) {
    event.preventDefault();
    _dialog.hide();
  }
}

var areYouSureDialog = new AreYouSureDialog();
